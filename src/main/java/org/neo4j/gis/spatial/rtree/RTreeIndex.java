/**
 * Copyright (c) 2002-2013 "Neo Technology," Network Engine for Objects in Lund
 * AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial.rtree;

import java.util.*;
import java.util.stream.Collectors;

import org.neo4j.gis.spatial.rtree.filter.SearchFilter;
import org.neo4j.gis.spatial.rtree.filter.SearchResults;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;

/**
 *
 */
public class RTreeIndex implements SpatialIndexWriter {

    public static final String INDEX_PROP_BBOX = "bbox";
    public static final String KEY_SPLIT = "splitMode";
    public static final String QUADRATIC_SPLIT = "quadratic";
    public static final String GREENES_SPLIT = "greene";

	private TreeMonitor monitor;
	// Constructor
	public RTreeIndex(GraphDatabaseService database, Node rootNode, EnvelopeDecoder envelopeEncoder) {
		this(database, rootNode, envelopeEncoder, 100);
	}
	public void addMonitor(TreeMonitor monitor){
		this.monitor=monitor;
	}

	public RTreeIndex(GraphDatabaseService database, Node rootNode, EnvelopeDecoder envelopeDecoder, int maxNodeReferences) {
		this.database = database;
		this.rootNode = rootNode;
		this.envelopeDecoder = envelopeDecoder;
		this.maxNodeReferences = maxNodeReferences;
        monitor=new EmptyMonitor();
		if (envelopeDecoder == null) {
			throw new NullPointerException("envelopeDecoder is NULL");
		}

		initIndexRoot();
		initIndexMetadata();
	}

	// Public methods
	@Override
	public EnvelopeDecoder getEnvelopeDecoder() {
		return this.envelopeDecoder;
	}

    public void configure(Map<String, Object> config) {
        for(String key:config.keySet()) {
            if(key.equals(KEY_SPLIT)) {
                String value = config.get(key).toString();
                switch(value) {
                    case QUADRATIC_SPLIT:
                    case GREENES_SPLIT:
                        splitMode = value;
                        break;
                    default:
                        throw new IllegalArgumentException("No such RTreeIndex value for '"+key+"': "+value);
                }
            }
        }
    }

	@Override
	public void add(Node geomNode) {
		// initialize the search with root
		Node parent = getIndexRoot();

		addBelow(parent, geomNode);

		countSaved = false;
		totalGeometryCount++;
	}

	/**
	 * This method will add the node somewhere below the parent.
	 * @param parent
	 * @param geomNode
	 */
	private void addBelow(Node parent, Node geomNode){
		// choose a path down to a leaf
		while (!nodeIsLeaf(parent)) {
			parent = chooseSubTree(parent, geomNode);
		}
        if (countChildren(parent, RTreeRelationshipTypes.RTREE_REFERENCE) >= maxNodeReferences) {
			insertInLeaf(parent, geomNode);
            splitAndAdjustPathBoundingBox(parent);
		} else {
			if (insertInLeaf(parent, geomNode)) {
				// bbox enlargement needed
				adjustPathBoundingBox(parent);
			}
		}
	}


	/**
	 * Use this method if you want to insert an index node as a child of a given index node. This will recursively
	 * update the bounding boxes above the parent to keep the tree consistent.
	 * @param parent
	 * @param child
	 * @return true if parent bounding box was / has to be expanded
	 */
	private boolean insertIndexNodeOnParent(Node parent, Node child) {
		int numChildren = countChildren(parent, RTreeRelationshipTypes.RTREE_CHILD);
		boolean needExpansion = addChild(parent, RTreeRelationshipTypes.RTREE_CHILD, child);
		if (numChildren < maxNodeReferences) {
			if (needExpansion) {
				adjustPathBoundingBox(parent);
			}
		} else {
			splitAndAdjustPathBoundingBox(parent);
		}
		return needExpansion;
	}


	/**
	 * Depending on the size of the incumbent tree, this will either attempt to rebuild the entire index from scratch
	 * (strategy used if the insert larger than 40% of the current tree size - may give heap out of memory errors for
	 * large inserts as has O(n) space complexity in the total tree size. It has nlog(n) time complexity. See fuction
	 * partition for more details.) or it will insert using the method of seeded clustering, where you attempt to use the
	 * existing tree structure to partition your data.
	 * <p>
	 * This is based on the Paper "Bulk Insertion for R-trees by seeded clustering" by T.Lee, S.Lee & B Moon.
	 * Repeated use of this strategy will lead to degraded query performance, especially if used for
	 * many relatively small insertions compared to tree size. Though not worse than one by one insertion.
	 * In practice, it should be fine for most uses.
	 *
	 * @param geomNodes
	 */
	@Override
	public void add(List<Node> geomNodes) {

		//If the insertion is large relative to the size of the tree, simply rebuild the whole tree.
		if (geomNodes.size() > totalGeometryCount * 0.4) {
            monitor.addNbrRebuilt();
            List<Node> nodesToAdd = new ArrayList<>(geomNodes.size() + totalGeometryCount);
			for (Node n : getAllIndexedNodes()) {
				nodesToAdd.add(n);
			}
			nodesToAdd.addAll(geomNodes);
			for (Node n : getAllIndexInternalNodes()) {
				if (!n.equals(getIndexRoot())) {
					deleteNode(n);
				}
			}
			buildRtreeFromScratch(getIndexRoot(), decodeEnvelopes(nodesToAdd), 0.7);
			countSaved = false;
			totalGeometryCount = nodesToAdd.size();
		} else {

			List<NodeWithEnvelope> outliers = bulkInsertion(getIndexRoot(), getHeight(getIndexRoot(), 0), decodeEnvelopes(geomNodes), 0.7);
			countSaved = false;
			totalGeometryCount = totalGeometryCount + (geomNodes.size() - outliers.size());
			for (NodeWithEnvelope n : outliers) {
				add(n.node);
			}
		}
	}

	private List<NodeWithEnvelope> decodeEnvelopes(List<Node> nodes) {
		return nodes.stream().map(NodeWithEnvelope::new).collect(Collectors.toList());
	}

	class NodeWithEnvelope {
		Envelope envelope;
		Node node;
		NodeWithEnvelope(Node node) {
			this.node = node;
			this.envelope = envelopeDecoder.decodeEnvelope(node);
		}
	}

	/**
	 * Comparator for comparing nodes by compaing the xMin on their evelopes.
	 */
	public static class ComparatorOnXMin implements Comparator<NodeWithEnvelope> {

		@Override
		public int compare(NodeWithEnvelope o1, NodeWithEnvelope o2) {
			return Double.compare(o1.envelope.getMinX(), o2.envelope.getMinX());
		}
	}

	/**
	 * Comparator or comparing nodes by coparing the yMin on their envelopes.
	 */
	public static class ComparatorOnYMin implements Comparator<NodeWithEnvelope> {

		@Override
		public int compare(NodeWithEnvelope o1, NodeWithEnvelope o2) {
			return Double.compare(o1.envelope.getMinY(), o2.envelope.getMinY());
		}
	}

	/**
	 * Returns the height of the tree, starting with the rootNode and adding one for each subsequent level. Relies on the
	 * balanced property of the RTree that all leaves are on the same level and no index nodes are empty. In the convention
     * the index is level 0, so if there is just the index and the leaf nodes, the leaf nodes are level one and the height is one.
     * Thus the lowest level is 1.
	 *
	 * @param rootNode
	 * @param height
	 * @return
	 */
	int getHeight(Node rootNode, int height) {
		Iterator<Relationship> rels = rootNode.getRelationships(Direction.OUTGOING, RTreeRelationshipTypes.RTREE_CHILD).iterator();
		if (rels.hasNext()) {
			return getHeight(rels.next().getEndNode(), height + 1);
		} else {
            // Add one to account for the step to leaf nodes.
			return height + 1; // todo should this really be +1 ?
		}
	}

	List<Node> getIndexChildren(Node rootNode) {
		List<Node> result = new ArrayList<>();
		for (Relationship r : rootNode.getRelationships(Direction.OUTGOING, RTreeRelationshipTypes.RTREE_CHILD)) {
			result.add(r.getEndNode());
		}
		return result;
	}

	private List<Node> getIndexChildren(Node rootNode, int depth) {
		if (depth < 1) {
			throw new IllegalArgumentException("Depths must be at least one");
		}
		List<Node> rootChildren = getIndexChildren(rootNode);
		if (depth == 1) {
			return rootChildren;
		} else {
			List<Node> result = new ArrayList<>(rootChildren.size() * 5);
			for (Node child : rootChildren) {
				result.addAll(getIndexChildren(child, depth - 1));
			}
			return result;
		}
	}

	private List<NodeWithEnvelope> bulkInsertion(Node rootNode, int rootNodeHeight, final List<NodeWithEnvelope> geomNodes, final double loadingFactor) {
		List<Node> children = getIndexChildren(rootNode);
		if(children.isEmpty()){
			return geomNodes;
		}
		children.sort(new IndexNodeAreaComparator());

		Map<Node, List<NodeWithEnvelope>> map = new HashMap<>(children.size());
		Map<Node, Envelope> envelopes = new HashMap<>(children.size());
		int nodesPerRootSubTree = Math.max(16, geomNodes.size() / children.size());
		for (Node n : children) {
			map.put(n, new ArrayList<>(nodesPerRootSubTree));
			envelopes.put(n, getIndexNodeEnvelope(n));
		}

		// The outliers are those nodes which do not fit into the existing tree hierarchy.
		List<NodeWithEnvelope> outliers = new ArrayList<>(geomNodes.size() / 10); // 10% outliers
		for (NodeWithEnvelope n : geomNodes) {
			Envelope env = n.envelope;
			boolean flag = true;

			//exploits that the iterator returns the list inorder, which is sorted by size, as above. Thus child
			//is always added to the smallest existing envelope which contains it.
			for (Node c : children) {
				if (envelopes.get(c).contains(env)) {
					map.get(c).add(n); //add to smallest area envelope which contains the child;
					flag = false;
					break;
				}
			}
			// else add to outliers.
			if (flag) {
				outliers.add(n);
			}
		}
		for (Node child : children) {
			List<NodeWithEnvelope> cluster = map.get(child);

			if (cluster.isEmpty()) continue;

			// todo move each branch into a named method
			int expectedHeight = expectedHeight(loadingFactor, cluster.size());

            //In an rtree is this height it will add as a single child to the current child node.
            int currentRTreeHeight = rootNodeHeight - 2;
			if(expectedHeight-currentRTreeHeight > 1 ){
				throw new RuntimeException("Due to h_i-l_t > 1");
			}
			if (expectedHeight < currentRTreeHeight) {
				monitor.addCase("h_i < l_t ");
                //if the height is smaller than that recursively sort and split.
				outliers.addAll(bulkInsertion(child, rootNodeHeight - 1, cluster, loadingFactor));
			} //if constructed tree is the correct size insert it here.
			else if (expectedHeight == currentRTreeHeight) {

				//Do not create underfull nodes, instead use the add logic, except we know the root not to add them too.
                //this handles the case where the number of nodes in a cluster is small.

				if (cluster.size() < maxNodeReferences * loadingFactor / 2) {
					monitor.addCase("h_i == l_t && small cluster");
					// getParent because addition might cause a split. This strategy not ideal,
					// but does tend to limit overlap more than adding to the child exclusively.

					for (NodeWithEnvelope n : cluster) {
						addBelow(rootNode, n.node);
					}
				} else {
					monitor.addCase("h_i == l_t && big cluster");
					Node newRootNode = database.createNode();
					buildRtreeFromScratch(newRootNode, cluster, loadingFactor);
					insertIndexNodeOnParent(child, newRootNode);

				}

			} else {
                Node newRootNode = database.createNode();
				buildRtreeFromScratch(newRootNode, cluster, loadingFactor);
				int newHeight = getHeight(newRootNode, 0);
				if (newHeight == 1) {
					monitor.addCase("h_i > l_t (d==1)");
					for (Relationship geom : newRootNode.getRelationships(RTreeRelationshipTypes.RTREE_REFERENCE)) {
						addBelow(child, geom.getEndNode());
						geom.delete();
					}
				} else {
					monitor.addCase("h_i > l_t (d>1)");
					int insertDepth = newHeight - (currentRTreeHeight);
					List<Node> childrenToBeInserted = getIndexChildren(newRootNode, insertDepth);
					for (Node n : childrenToBeInserted) {
						Relationship relationship = n.getSingleRelationship(RTreeRelationshipTypes.RTREE_CHILD, Direction.INCOMING);
						relationship.delete();
						insertIndexNodeOnParent(child, n);
					}
				}
//                System.out.println("deleting tmp tree at: "+newRootNode.getId()+" ("+childrenToBeInserted.stream()
//                        .map(Node::getId).toArray()+")");
//                System.out.print("deleting tmp tree at: "+newRootNode.getId()+" ( ");
//                List<String> outList= new ArrayList<>( );
//				Iterator<Relationship> itr = newRootNode.getRelationships().iterator();
//				while(itr.hasNext()){
//					Iterator<Relationship> tmpItr=itr.next().getEndNode().getRelationships(Direction.OUTGOING).iterator();
//					while(tmpItr.hasNext()){
//						outList.add(tmpItr.next().toString());
//					}
//				}
//                for (Node n : childrenToBeInserted) {
//                    outList.add(n.getId());
//                }
//                java.util.Collections.sort( outList );
//
//                for (String n : outList) {
//                    System.out.print(n + ", ");
//                }
//                System.out.println(" )");
//				System.out.println("Deleting sub-tree:\n" + database.execute("MATCH p=(root)-[:RTREE_CHILD*0..]-(index)-[:RTREE_REFERENCE]->(geom) WHERE id(root)="+newRootNode.getId()+" return length(p), count(*)").resultAsString());

				// todo wouldn't it be better for this temporary tree to only live in memory?
				deleteRecursivelySubtree(newRootNode, null); // remove the buffer tree remnants
			}
		}

		return outliers;
	}


	private int expectedHeight(double loadingFactor, int size) {
        if (size == 1) {
            return 1;
        } else {
            final int targetLoading = (int) Math.floor(maxNodeReferences * loadingFactor);
            return (int) Math.ceil(Math.log(size) / Math.log(targetLoading)); //exploit change of base formula
        }

	}

	/**
	 * This algorithm is based on Overlap Minimizing Top-down Bulk Loading Algorithm for R-tree by T Lee and S Lee.
	 * This is effectively a wrapper function around the function Partition which will attempt to parallelise the task.
	 * This can work better or worse since the top level may have as few as two nodes, in which case it fails is not optimal.
	 * //TODO - Better parallelisation strategy.
	 *
	 * @param rootNode
	 * @param geomNodes
	 * @param loadingFactor - Must be between 0.1 and 1, this is how full each node will be, approximately.
	 *                      Use 1 for static trees, lower numbers if there are to be many subsequent updates.
	 */
	private void buildRtreeFromScratch(Node rootNode, final List<NodeWithEnvelope> geomNodes, double loadingFactor) {
		partition(rootNode, geomNodes, 0, loadingFactor);
	}

	/**
	 * This will partition a node into
	 *
	 * @param rootNode
	 * @param nodes
	 * @param depth
	 * @param loadingFactor - what fraction of the max references will be filled.
	 * @return
	 */
	private boolean partition(Node rootNode, List<NodeWithEnvelope> nodes, int depth, final double loadingFactor) {
		Comparator<NodeWithEnvelope> comparator = (depth % 2 == 0) ?
				new ComparatorOnXMin() :
				new ComparatorOnYMin();
		nodes.sort(comparator);

		//work out the number of times to partition it:
		final int targetLoading = (int) Math.round(maxNodeReferences * loadingFactor);
		int nodeCount = nodes.size();


		boolean expandRootNodeBoundingBox = false;
		if (nodeCount <= targetLoading) {
			for (NodeWithEnvelope n : nodes) {
				expandRootNodeBoundingBox |= insertInLeaf(rootNode, n.node);
			}
			if (expandRootNodeBoundingBox) {
				adjustPathBoundingBox(rootNode);
			}
		} else {
			final int height = expectedHeight(loadingFactor, nodeCount); //exploit change of base formula
			monitor.addSplit();
			final int subTreeSize = (int) Math.round(Math.pow(targetLoading, height - 1));
			final int numberOfPartitions = (int) Math.ceil((double) nodeCount / (double) subTreeSize);
			// - TODO change this to use the sort function above
			List<List<NodeWithEnvelope>> partitions = partitionList(nodes, numberOfPartitions);

			//recurse on each partition
			for (List<NodeWithEnvelope> partition : partitions) {
				Node newIndexNode = database.createNode();
				expandRootNodeBoundingBox |= partition(newIndexNode, partition, depth + 1, loadingFactor);
				expandRootNodeBoundingBox |= insertIndexNodeOnParent(rootNode, newIndexNode);
			}
		}
		return expandRootNodeBoundingBox;
	}

	// quick dirty way to partition a set into equal sized disjoint subsets
	// - TODO why not use list.sublist() without copying ?

	private List<List<NodeWithEnvelope>> partitionList(List<NodeWithEnvelope> nodes, int numberOfPartitions) {
		int nodeCount = nodes.size();
		List<List<NodeWithEnvelope>> partitions = new ArrayList<>(numberOfPartitions);

		int partitionSize = nodeCount / numberOfPartitions; //it is critical that partitionSize is always less than the target loading.
        if (nodeCount % numberOfPartitions > 0) {
            partitionSize++;
        }
		for (int i = 0; i < numberOfPartitions; i++) {
			partitions.add(nodes.subList(i*partitionSize,Math.min((i+1)*partitionSize,nodeCount)));
        }
		return partitions;
	}

	@Override
	public void remove(long geomNodeId, boolean deleteGeomNode) {
		remove(geomNodeId, deleteGeomNode, true);
	}

	public void remove(long geomNodeId, boolean deleteGeomNode, boolean throwExceptionIfNotFound) {
		
		Node geomNode = null;
		// getNodeById throws NotFoundException if node is already removed
		try {
			geomNode = database.getNodeById(geomNodeId);
			
		} catch (NotFoundException nfe) {
			
			// propagate exception only if flag is set
			if (throwExceptionIfNotFound) {
				throw nfe;
			}
		}
		
		if (geomNode == null && !throwExceptionIfNotFound) {
			//fail silently
			return;
		}

		// be sure geomNode is inside this RTree
		Node indexNode = findLeafContainingGeometryNode(geomNode, throwExceptionIfNotFound);
		if (indexNode == null) {
			return;
		}

		// remove the entry 
		final Relationship geometryRtreeReference = geomNode.getSingleRelationship(RTreeRelationshipTypes.RTREE_REFERENCE, Direction.INCOMING);
		if (geometryRtreeReference != null) {
			geometryRtreeReference.delete();
		}
		if (deleteGeomNode) {
			deleteNode(geomNode);
		}

		// reorganize the tree if needed
		if (countChildren(indexNode, RTreeRelationshipTypes.RTREE_REFERENCE) == 0) {
			indexNode = deleteEmptyTreeNodes(indexNode, RTreeRelationshipTypes.RTREE_REFERENCE);
			adjustParentBoundingBox(indexNode, RTreeRelationshipTypes.RTREE_CHILD);
		} else {
			adjustParentBoundingBox(indexNode, RTreeRelationshipTypes.RTREE_REFERENCE);
		}

		adjustPathBoundingBox(indexNode);

		countSaved = false;
		totalGeometryCount--;
	}

	private Node deleteEmptyTreeNodes(Node indexNode, RelationshipType relType) {
		if (countChildren(indexNode, relType) == 0) {
			Node parent = getIndexNodeParent(indexNode);
			if (parent != null) {
				indexNode.getSingleRelationship(RTreeRelationshipTypes.RTREE_CHILD, Direction.INCOMING).delete();
				
				indexNode.delete();
				return deleteEmptyTreeNodes(parent, RTreeRelationshipTypes.RTREE_CHILD);
			} else {
				// root
				return indexNode;
			}
		} else {
			return indexNode;
		}
	}

	@Override
	public void removeAll(final boolean deleteGeomNodes, final Listener monitor) {
		Node indexRoot = getIndexRoot();

		monitor.begin(count());
		try {
			// delete all geometry nodes
			visitInTx(new SpatialIndexVisitor() {
				public boolean needsToVisit(Envelope indexNodeEnvelope) {
					return true;
				}

				public void onIndexReference(Node geomNode) {
					geomNode.getSingleRelationship(RTreeRelationshipTypes.RTREE_REFERENCE, Direction.INCOMING).delete();
					if (deleteGeomNodes) {
						deleteNode(geomNode);
					}

					monitor.worked(1);
				}
			}, indexRoot.getId());
		} finally {
			monitor.done();
		}

		try (Transaction tx = database.beginTx()) {
			// delete index root relationship
			indexRoot.getSingleRelationship(RTreeRelationshipTypes.RTREE_ROOT, Direction.INCOMING).delete();

			// delete tree
			deleteRecursivelySubtree(indexRoot,null);

			// delete tree metadata
			Relationship metadataNodeRelationship = getRootNode().getSingleRelationship(RTreeRelationshipTypes.RTREE_METADATA, Direction.OUTGOING);
			Node metadataNode = metadataNodeRelationship.getEndNode();
			metadataNodeRelationship.delete();
			metadataNode.delete();

			tx.success();
		}

		countSaved = false;
		totalGeometryCount = 0;
	}

	@Override
	public void clear(final Listener monitor) {
		try (Transaction tx = database.beginTx()) {
			removeAll(false, new NullListener());
			initIndexRoot();
			initIndexMetadata();
			tx.success();
		}
	}

	@Override
	public Envelope getBoundingBox() {
		try (Transaction tx = database.beginTx()) {
			Envelope result = getIndexNodeEnvelope(getIndexRoot());
			tx.success();
			return result;
		}
	}

	@Override
	public int count() {
		saveCount();
		return totalGeometryCount;
	}

	@Override
	public boolean isEmpty() {
		Node indexRoot = getIndexRoot();
		return !indexRoot.hasProperty(INDEX_PROP_BBOX);
	}

	@Override
	public boolean isNodeIndexed(Long geomNodeId) {
		Node geomNode = database.getNodeById(geomNodeId);
		// be sure geomNode is inside this RTree
		return findLeafContainingGeometryNode(geomNode, false) != null;
	}

	public void warmUp() {
		visit(new WarmUpVisitor(), getIndexRoot());
	}

	public Iterable<Node> getAllIndexInternalNodes()
	{
		TraversalDescription td = database.traversalDescription()
				.breadthFirst()
				.relationships( RTreeRelationshipTypes.RTREE_CHILD, Direction.OUTGOING )
				.evaluator( Evaluators.all() );
        return td.traverse( getIndexRoot() ).nodes();
	}

	@Override
	public Iterable<Node> getAllIndexedNodes() {
		return new IndexNodeToGeometryNodeIterable(getAllIndexInternalNodes());
	}

	private class SearchEvaluator implements Evaluator
	{
		private SearchFilter filter;

		public SearchEvaluator(SearchFilter filter) {
			this.filter = filter;
		}

		@Override
        public Evaluation evaluate( Path path )
        {
            Relationship rel = path.lastRelationship();
            Node node = path.endNode();
            if ( rel == null )
            {
                return Evaluation.EXCLUDE_AND_CONTINUE;
            }
            else if ( rel.isType( RTreeRelationshipTypes.RTREE_CHILD ) )
            {
				boolean shouldContinue = filter.needsToVisit( getIndexNodeEnvelope( node ) );
				if(shouldContinue) monitor.matchedTreeNode(path.length(), node);
				monitor.addCase(shouldContinue ? "Index Matches" : "Index Does NOT Match");
                return shouldContinue ?
                       Evaluation.EXCLUDE_AND_CONTINUE :
                       Evaluation.EXCLUDE_AND_PRUNE;
            }
            else if ( rel.isType( RTreeRelationshipTypes.RTREE_REFERENCE ) )
            {
				boolean found = filter.geometryMatches( node );
				monitor.addCase(found ? "Geometry Matches" : "Geometry Does NOT Match");
                return found ?
                       Evaluation.INCLUDE_AND_PRUNE :
                       Evaluation.EXCLUDE_AND_PRUNE;
            }
            return null;
        }
    }

	public SearchResults searchIndex(SearchFilter filter) {
		try (Transaction tx = database.beginTx()) {
			SearchEvaluator searchEvaluator = new SearchEvaluator(filter);
			TraversalDescription td = database.traversalDescription()
					.depthFirst()
					.relationships( RTreeRelationshipTypes.RTREE_CHILD, Direction.OUTGOING )
					.relationships( RTreeRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING )
					.evaluator( searchEvaluator );
            Traverser traverser = td.traverse( getIndexRoot() );
            SearchResults results = new SearchResults( traverser.nodes() );
            tx.success();
            return results;
		}
	}

	public void visit(SpatialIndexVisitor visitor, Node indexNode) {
		if (!visitor.needsToVisit(getIndexNodeEnvelope(indexNode))) {
			return;
		}

		try (Transaction tx = database.beginTx()) {
			if (indexNode.hasRelationship(RTreeRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
				// Node is not a leaf
				for (Relationship rel : indexNode.getRelationships(RTreeRelationshipTypes.RTREE_CHILD,
					Direction.OUTGOING)) {
					Node child = rel.getEndNode();
					// collect children results
					visit(visitor, child);
				}
			} else if (indexNode.hasRelationship(RTreeRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)) {
				// Node is a leaf
				for (Relationship rel : indexNode.getRelationships(RTreeRelationshipTypes.RTREE_REFERENCE,
					Direction.OUTGOING)) {
					visitor.onIndexReference(rel.getEndNode());
				}
			}
			tx.success();
		}
	}

	public Node getIndexRoot() {
		try (Transaction tx = database.beginTx()) {
			Node indexRoot = getRootNode().getSingleRelationship(RTreeRelationshipTypes.RTREE_ROOT, Direction.OUTGOING)
				.getEndNode();
			tx.success();
			return indexRoot;
		}
	}

	// Private methods

	/***
	 * This will get the envelope of the child. The relationshipType acts as as flag to allow the function to
	 * know whether the child is a leaf or an index node.
	 * @param child
	 * @param relType
     * @return
     */
	private Envelope getChildNodeEnvelope(Node child, RelationshipType relType) {
		if (relType.name().equals(RTreeRelationshipTypes.RTREE_REFERENCE.name())) {
			return getLeafNodeEnvelope(child);
		} else {
			return getIndexNodeEnvelope(child);
		}
	}

	/**
	 * The leaf nodes belong to the domain model, and as such need to use
	 * the layers domain-specific GeometryEncoder for decoding the envelope.
	 */
	private Envelope getLeafNodeEnvelope(Node geomNode) {
		return envelopeDecoder.decodeEnvelope(geomNode);
	}

	/**
	 * The index nodes do NOT belong to the domain model, and as such need
	 * to use the indexes internal knowledge of the index tree and node
	 * structure for decoding the envelope.
	 */
	public Envelope getIndexNodeEnvelope(Node indexNode) {
		if (indexNode == null) {
			indexNode = getIndexRoot();
		}
		try (Transaction tx = database.beginTx()) {
			if (!indexNode.hasProperty(INDEX_PROP_BBOX)) {
				// this is ok after an index node split
				tx.success();
				return null;
			}

			double[] bbox = (double[]) indexNode.getProperty(INDEX_PROP_BBOX);
			tx.success();
			// Envelope parameters: xmin, xmax, ymin, ymax
			return new Envelope(bbox[0], bbox[2], bbox[1], bbox[3]);
		}
	}

	private void visitInTx(SpatialIndexVisitor visitor, Long indexNodeId) {
		Node indexNode = database.getNodeById(indexNodeId);
		if (!visitor.needsToVisit(getIndexNodeEnvelope(indexNode))) {
			return;
		}

		if (indexNode.hasRelationship(RTreeRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
			// Node is not a leaf

			// collect children
			List<Long> children = new ArrayList<>();
			for (Relationship rel : indexNode.getRelationships(RTreeRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
				children.add(rel.getEndNode().getId());
			}


			// visit children
			for (Long child : children) {
				visitInTx(visitor, child);
			}
		} else if (indexNode.hasRelationship(RTreeRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)) {
			// Node is a leaf
			try (Transaction tx = database.beginTx()) {
				for (Relationship rel : indexNode.getRelationships(RTreeRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING)) {
					visitor.onIndexReference(rel.getEndNode());
				}

				tx.success();
			}
		}
	}

	private void initIndexMetadata() {
		Node layerNode = getRootNode();
		if (layerNode.hasRelationship(RTreeRelationshipTypes.RTREE_METADATA, Direction.OUTGOING)) {
			// metadata already present
			metadataNode = layerNode.getSingleRelationship(RTreeRelationshipTypes.RTREE_METADATA, Direction.OUTGOING).getEndNode();

			maxNodeReferences = (Integer) metadataNode.getProperty("maxNodeReferences");
		} else {
			// metadata initialization
			metadataNode = database.createNode();
			layerNode.createRelationshipTo(metadataNode, RTreeRelationshipTypes.RTREE_METADATA);

			metadataNode.setProperty("maxNodeReferences", maxNodeReferences);
		}

		saveCount();
	}

	private void initIndexRoot() {
		Node layerNode = getRootNode();
		if (!layerNode.hasRelationship(RTreeRelationshipTypes.RTREE_ROOT, Direction.OUTGOING)) {
			// index initialization
			Node root = database.createNode();
			layerNode.createRelationshipTo(root, RTreeRelationshipTypes.RTREE_ROOT);
		}
	}

	private Node getMetadataNode() {
		if (metadataNode == null) {
			metadataNode = getRootNode().getSingleRelationship(RTreeRelationshipTypes.RTREE_METADATA, Direction.OUTGOING).getEndNode();
		}

		return metadataNode;
	}

	/**
	 * Save the geometry count to the database if it has not been saved yet.
	 * However, if the count is zero, first do an exhaustive search of the
	 * tree and count everything before saving it.
	 */
	private void saveCount() {
		if (totalGeometryCount == 0) {
			SpatialIndexRecordCounter counter = new SpatialIndexRecordCounter();
			visit(counter, getIndexRoot());
			totalGeometryCount = counter.getResult();

			int savedGeometryCount = (int)getMetadataNode().getProperty("totalGeometryCount",0);
			countSaved = savedGeometryCount == totalGeometryCount;
		}

		if (!countSaved) {
			try (Transaction tx = database.beginTx()) {
				getMetadataNode().setProperty("totalGeometryCount", totalGeometryCount);
				countSaved = true;
				tx.success();
			}
		}
	}

	private boolean nodeIsLeaf(Node node) {
		return !node.hasRelationship(RTreeRelationshipTypes.RTREE_CHILD, Direction.OUTGOING);
	}

	private Node chooseSubTree(Node parentIndexNode, Node geomRootNode) {
		// children that can contain the new geometry
		List<Node> indexNodes = new ArrayList<>();

		// pick the child that contains the new geometry bounding box
		Iterable<Relationship> relationships = parentIndexNode.getRelationships(RTreeRelationshipTypes.RTREE_CHILD, Direction.OUTGOING);
		for (Relationship relation : relationships) {
			Node indexNode = relation.getEndNode();
			if (getIndexNodeEnvelope(indexNode).contains(getLeafNodeEnvelope(geomRootNode))) {
				indexNodes.add(indexNode);
			}
		}

		if (indexNodes.size() > 1) {
			return chooseIndexNodeWithSmallestArea(indexNodes);
		} else if (indexNodes.size() == 1) {
			return indexNodes.get(0);
		}

		// pick the child that needs the minimum enlargement to include the new geometry
		double minimumEnlargement = Double.POSITIVE_INFINITY;
		relationships = parentIndexNode.getRelationships(RTreeRelationshipTypes.RTREE_CHILD, Direction.OUTGOING);
		for (Relationship relation : relationships) {
			Node indexNode = relation.getEndNode();
			double enlargementNeeded = getAreaEnlargement(indexNode, geomRootNode);

			if (enlargementNeeded < minimumEnlargement) {
				indexNodes.clear();
				indexNodes.add(indexNode);
				minimumEnlargement = enlargementNeeded;
			} else if (enlargementNeeded == minimumEnlargement) {
				indexNodes.add(indexNode);
			}
		}

		if (indexNodes.size() > 1) {
			return chooseIndexNodeWithSmallestArea(indexNodes);
		} else if (indexNodes.size() == 1) {
			return indexNodes.get(0);
		} else {
			// this shouldn't happen
			throw new RuntimeException("No IndexNode found for new geometry");
		}
	}

	private double getAreaEnlargement(Node indexNode, Node geomRootNode) {
		Envelope before = getIndexNodeEnvelope(indexNode);

		Envelope after = getLeafNodeEnvelope(geomRootNode);
		after.expandToInclude(before);

		return getArea(after) - getArea(before);
	}

	private Node chooseIndexNodeWithSmallestArea(List<Node> indexNodes) {
		Node result = null;
		double smallestArea = -1;

		for (Node indexNode : indexNodes) {
			double area = getArea(getIndexNodeEnvelope(indexNode));
			if (result == null || area < smallestArea) {
				result = indexNode;
				smallestArea = area;
			}
		}

		return result;
	}

	private int countChildren(Node indexNode, RelationshipType relationshipType) {
		int counter = 0;
		Iterator<Relationship> iterator = indexNode.getRelationships(relationshipType, Direction.OUTGOING).iterator();
		while (iterator.hasNext()) {
			iterator.next();
			counter++;
		}
		return counter;
	}

	/**
	 * @return is enlargement needed?
	 */
	private boolean insertInLeaf(Node indexNode, Node geomRootNode) {
		return addChild(indexNode, RTreeRelationshipTypes.RTREE_REFERENCE, geomRootNode);
	}

	private void splitAndAdjustPathBoundingBox(Node indexNode) {
        monitor.addSplit();
        // create a new node and distribute the entries
        Node newIndexNode = splitMode.equals(GREENES_SPLIT) ? greenesSplit(indexNode) : quadraticSplit(indexNode);
		Node parent = getIndexNodeParent(indexNode);
//        System.out.println("spitIndex " + newIndexNode.getId());
//        System.out.println("parent " + parent.getId());
        if (parent == null) {
			// if indexNode is the root
			createNewRoot(indexNode, newIndexNode);
		} else {
			expandParentBoundingBoxAfterNewChild(parent, (double[]) indexNode.getProperty(INDEX_PROP_BBOX));

			addChild(parent, RTreeRelationshipTypes.RTREE_CHILD, newIndexNode);

			if (countChildren(parent, RTreeRelationshipTypes.RTREE_CHILD) > maxNodeReferences) {
				splitAndAdjustPathBoundingBox(parent);
			} else {
				adjustPathBoundingBox(parent);
			}
		}
	}

    private Node quadraticSplit(Node indexNode) {
        if (nodeIsLeaf(indexNode)) {
            return quadraticSplit(indexNode, RTreeRelationshipTypes.RTREE_REFERENCE);
        } else {
            return quadraticSplit(indexNode, RTreeRelationshipTypes.RTREE_CHILD);
        }
    }

    private Node greenesSplit(Node indexNode) {
        if (nodeIsLeaf(indexNode)) {
            return greenesSplit(indexNode, RTreeRelationshipTypes.RTREE_REFERENCE);
        } else {
            return greenesSplit(indexNode, RTreeRelationshipTypes.RTREE_CHILD);
        }
    }

    private Node[] mostDistantByDeadSpace(List<Node> entries, RelationshipType relationshipType) {
        Node seed1 = null;
        Node seed2 = null;
        double worst = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < entries.size(); ++i) {
            Node e = entries.get(i);
            Envelope eEnvelope = getChildNodeEnvelope(e, relationshipType);
            for (int j = i + 1; j < entries.size(); ++j) {
                Node e1 = entries.get(j);
                Envelope e1Envelope = getChildNodeEnvelope(e1, relationshipType);
                double deadSpace = eEnvelope.separation(e1Envelope);
                if (deadSpace > worst) {
                    worst = deadSpace;
                    seed1 = e;
                    seed2 = e1;
                }
            }
        }
        return new Node[]{seed1,seed2};
    }

	private Node greenesSplit(Node indexNode, RelationshipType relationshipType) {
		List<Node> entries = new ArrayList<>();

		Iterable<Relationship> relationships = indexNode.getRelationships(relationshipType, Direction.OUTGOING);
		for (Relationship relationship : relationships) {
			entries.add(relationship.getEndNode());
			relationship.delete();
		}

        // pick two seed entries such that the dead space is maximal
        Node[] seeds = mostDistantByDeadSpace(entries, relationshipType);

        // Choose dimension to split on based on seed separation
        Envelope oldEnvelope = getIndexNodeEnvelope(indexNode);
        Envelope env1 = getChildNodeEnvelope(seeds[0], relationshipType);
        Envelope env2 = getChildNodeEnvelope(seeds[1], relationshipType);
        int longestDimension = 0;
        double maxSeparation = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < oldEnvelope.getDimension(); i++) {
            double separation = env1.separation(env2);
            if (separation > maxSeparation) {
                maxSeparation = separation;
                longestDimension = i;
            }
        }

        // Sort the entries by the longest dimension and then create envelopes around left and right halves
        List<NodeWithEnvelope> nodeWithEnvelopes = decodeEnvelopes(entries);
        nodeWithEnvelopes.sort(new SingleDimensionNodeEnvelopeComparator(longestDimension));
        int splitAt = nodeWithEnvelopes.size() / 2;
        List<NodeWithEnvelope> left = nodeWithEnvelopes.subList(0,splitAt);
        List<NodeWithEnvelope> right = nodeWithEnvelopes.subList(splitAt,nodeWithEnvelopes.size());
//        Collector<NodeWithEnvelope, Envelope, Envelope> envelopeCollector =
//                Collector.of(
//                        () -> new Envelope(),                     // supplier of initial empty envelope
//                        (j, p) -> j.expandToInclude(p.envelope),  // accumulator adding entries to envelope
//                        (j1, j2) -> j1.expandToInclude(j2),       // combiner of two envelopes
//                        (e) -> (e));                              // finisher returning final envelope
//
//        Envelope leftEnv = left.stream().collect(envelopeCollector);
//        Envelope rightEnv = right.stream().collect(envelopeCollector);

        return splitIntoTwoGroups(indexNode,
                left.stream().map(e -> e.node).collect(Collectors.toList()),
                right.stream().map(e -> e.node).collect(Collectors.toList()),
                relationshipType);
    }

    private static class SingleDimensionNodeEnvelopeComparator implements Comparator<NodeWithEnvelope> {
        private final int dimension;

        public SingleDimensionNodeEnvelopeComparator(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public int compare(NodeWithEnvelope o1, NodeWithEnvelope o2) {
            double length = o2.envelope.centre(dimension) - o1.envelope.centre(dimension);
            if (length < 0.0) return -1;
            else if (length > 0.0) return 1;
            else return 0;
        }
    }

	private Node quadraticSplit(Node indexNode, RelationshipType relationshipType) {
		List<Node> entries = new ArrayList<>();

		Iterable<Relationship> relationships = indexNode.getRelationships(relationshipType, Direction.OUTGOING);
		for (Relationship relationship : relationships) {
			entries.add(relationship.getEndNode());
			relationship.delete();
		}

		// pick two seed entries such that the dead space is maximal
        Node[] seeds = mostDistantByDeadSpace(entries, relationshipType);

		List<Node> group1 = new ArrayList<>();
		group1.add(seeds[0]);
		Envelope group1envelope = getChildNodeEnvelope(seeds[0], relationshipType);

		List<Node> group2 = new ArrayList<>();
		group2.add(seeds[1]);
		Envelope group2envelope = getChildNodeEnvelope(seeds[1], relationshipType);

		entries.remove(seeds[0]);
		entries.remove(seeds[1]);
		while (entries.size() > 0) {
			// compute the cost of inserting each entry
			List<Node> bestGroup = null;
			Envelope bestGroupEnvelope = null;
			Node bestEntry = null;
			double expansionMin = Double.POSITIVE_INFINITY;
			for (Node e : entries) {
				Envelope nodeEnvelope = getChildNodeEnvelope(e, relationshipType);
				double expansion1 = getArea(createEnvelope(nodeEnvelope, group1envelope)) - getArea(group1envelope);
				double expansion2 = getArea(createEnvelope(nodeEnvelope, group2envelope)) - getArea(group2envelope);

				if (expansion1 < expansion2 && expansion1 < expansionMin) {
					bestGroup = group1;
					bestGroupEnvelope = group1envelope;
					bestEntry = e;
					expansionMin = expansion1;
				} else if (expansion2 < expansion1 && expansion2 < expansionMin) {
					bestGroup = group2;
					bestGroupEnvelope = group2envelope;
					bestEntry = e;
					expansionMin = expansion2;
				} else if (expansion1 == expansion2 && expansion1 < expansionMin) {
					// in case of equality choose the group with the smallest area
					if (getArea(group1envelope) < getArea(group2envelope)) {
						bestGroup = group1;
						bestGroupEnvelope = group1envelope;
					} else {
						bestGroup = group2;
						bestGroupEnvelope = group2envelope;
					}
					bestEntry = e;
					expansionMin = expansion1;
				}
			}

			// insert the best candidate entry in the best group
			bestGroup.add(bestEntry);
			bestGroupEnvelope.expandToInclude(getChildNodeEnvelope(bestEntry, relationshipType));

			entries.remove(bestEntry);
		}

		return splitIntoTwoGroups(indexNode, group1, group2, relationshipType);
	}

    private Node splitIntoTwoGroups(Node indexNode, List<Node> group1, List<Node> group2, RelationshipType relationshipType) {
        // reset bounding box and add new children
        indexNode.removeProperty(INDEX_PROP_BBOX);
        for (Node node : group1) {
            addChild(indexNode, relationshipType, node);
        }

        // create new node from split
        Node newIndexNode = database.createNode();
        for (Node node : group2) {
            addChild(newIndexNode, relationshipType, node);
        }

        return newIndexNode;
    }

	private void createNewRoot(Node oldRoot, Node newIndexNode) {
		Node newRoot = database.createNode();
		addChild(newRoot, RTreeRelationshipTypes.RTREE_CHILD, oldRoot);
		addChild(newRoot, RTreeRelationshipTypes.RTREE_CHILD, newIndexNode);

		Node layerNode = getRootNode();
		layerNode.getSingleRelationship(RTreeRelationshipTypes.RTREE_ROOT, Direction.OUTGOING).delete();
		layerNode.createRelationshipTo(newRoot, RTreeRelationshipTypes.RTREE_ROOT);
	}

	private boolean addChild(Node parent, RelationshipType type, Node newChild) {
		Envelope childEnvelope = getChildNodeEnvelope(newChild, type);
		double[] childBBox = new double[]{
			childEnvelope.getMinX(), childEnvelope.getMinY(),
			childEnvelope.getMaxX(), childEnvelope.getMaxY()};
		parent.createRelationshipTo(newChild, type);
		return expandParentBoundingBoxAfterNewChild(parent, childBBox);
	}

	private void adjustPathBoundingBox(Node node) {
		Node parent = getIndexNodeParent(node);
		if (parent != null) {
			if (adjustParentBoundingBox(parent, RTreeRelationshipTypes.RTREE_CHILD)) {
				// entry has been modified: adjust the path for the parent
				adjustPathBoundingBox(parent);
			}
		}
	}

	/**
	 * Fix an IndexNode bounding box after a child has been removed
	 *
	 * @param indexNode
	 * @return true if something has changed
	 */
	private boolean adjustParentBoundingBox(Node indexNode, RelationshipType relationshipType) {
		double[] old = null;
		if (indexNode.hasProperty(INDEX_PROP_BBOX)) {
			old = (double[]) indexNode.getProperty(INDEX_PROP_BBOX);
		}

		Envelope bbox = null;

		Iterator<Relationship> iterator = indexNode.getRelationships(relationshipType, Direction.OUTGOING).iterator();
		while (iterator.hasNext()) {
			Node childNode = iterator.next().getEndNode();

			if (bbox == null) {
				bbox = new Envelope(getChildNodeEnvelope(childNode, relationshipType));
			} else {
				bbox.expandToInclude(getChildNodeEnvelope(childNode, relationshipType));
			}
		}

		if (bbox == null) {
			// this could happen in an empty tree
			bbox = new Envelope(0, 0, 0, 0);
		}

		if (old == null || old.length != 4
			|| bbox.getMinX() != old[0]
			|| bbox.getMinY() != old[1]
			|| bbox.getMaxX() != old[2]
			|| bbox.getMaxY() != old[3]) {
			indexNode.setProperty(INDEX_PROP_BBOX, new double[]{bbox.getMinX(), bbox.getMinY(), bbox.getMaxX(), bbox.getMaxY()});
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Adjust IndexNode bounding box according to the new child inserted
	 *
	 * @param parent IndexNode
	 * @param childBBox geomNode inserted
	 * @return is bbox changed?
	 */
	private boolean expandParentBoundingBoxAfterNewChild(Node parent, double[] childBBox) {
		if (!parent.hasProperty(INDEX_PROP_BBOX)) {
			parent.setProperty(INDEX_PROP_BBOX, new double[]{childBBox[0], childBBox[1], childBBox[2], childBBox[3]});
			return true;
		}

		double[] parentBBox = (double[]) parent.getProperty(INDEX_PROP_BBOX);

		boolean valueChanged = setMin(parentBBox, childBBox, 0);
		valueChanged = setMin(parentBBox, childBBox, 1) || valueChanged;
		valueChanged = setMax(parentBBox, childBBox, 2) || valueChanged;
		valueChanged = setMax(parentBBox, childBBox, 3) || valueChanged;

		if (valueChanged) {
			parent.setProperty(INDEX_PROP_BBOX, parentBBox);
		}

		return valueChanged;
	}

	private boolean setMin(double[] parent, double[] child, int index) {
		if (parent[index] > child[index]) {
			parent[index] = child[index];
			return true;
		} else {
			return false;
		}
	}

	private boolean setMax(double[] parent, double[] child, int index) {
		if (parent[index] < child[index]) {
			parent[index] = child[index];
			return true;
		} else {
			return false;
		}
	}

	private Node getIndexNodeParent(Node indexNode) {
		Relationship relationship = indexNode.getSingleRelationship(RTreeRelationshipTypes.RTREE_CHILD, Direction.INCOMING);
		if (relationship == null) {
			return null;
		} else {
			return relationship.getStartNode();
		}
	}

	private double getArea(Envelope e) {
		return e.getWidth() * e.getHeight();
		// TODO why not e.getArea(); ?
	}

	private void deleteRecursivelySubtree(Node node, Relationship incoming) {
		for (Relationship relationship : node.getRelationships(RTreeRelationshipTypes.RTREE_CHILD, Direction.OUTGOING)) {
			deleteRecursivelySubtree(relationship.getEndNode(),relationship);
		}
		if (incoming!=null) {
			incoming.delete();
		}
		Iterator<Relationship> itr = node.getRelationships().iterator();
		while (itr.hasNext()) {
			Relationship rel = itr.next();
			System.out.println("Unexpected relationship found on " + node + ": " + rel.toString());
			rel.delete();
		}
		node.delete();
	}

	protected Node findLeafContainingGeometryNode(Node geomNode, boolean throwExceptionIfNotFound) {
		if (!geomNode.hasRelationship(RTreeRelationshipTypes.RTREE_REFERENCE, Direction.INCOMING)) {
			if (throwExceptionIfNotFound) {
				throw new RuntimeException("GeometryNode not indexed with an RTree: " + geomNode.getId());
			} else {
				return null;
			}
		}

		Node indexNodeLeaf = geomNode.getSingleRelationship(RTreeRelationshipTypes.RTREE_REFERENCE, Direction.INCOMING).getStartNode();

		Node root = null;
		Node child = indexNodeLeaf;
		while (root == null) {
			Node parent = getIndexNodeParent(child);
			if (parent == null) {
				root = child;
			} else {
				child = parent;
			}
		}

		if (root.getId() != getIndexRoot().getId()) {
			if (throwExceptionIfNotFound) {
				throw new RuntimeException("GeometryNode not indexed in this RTree: " + geomNode.getId());
			} else {
				return null;
			}
		} else {
			return indexNodeLeaf;
		}
	}

	private void deleteNode(Node node) {
		for (Relationship r : node.getRelationships()) {
			r.delete();
		}
		node.delete();
	}

	private Node getRootNode() {
		return rootNode;
	}

	/**
	 * Create a bounding box encompassing the two bounding boxes passed in.
	 */
	private static Envelope createEnvelope(Envelope e, Envelope e1) {
		Envelope result = new Envelope(e);
		result.expandToInclude(e1);
		return result;
	}

	// Attributes
	public GraphDatabaseService getDatabase() {
		return database;
	}

	private GraphDatabaseService database;

	private Node rootNode;
	private EnvelopeDecoder envelopeDecoder;
	private int maxNodeReferences;
    private String splitMode = QUADRATIC_SPLIT;

	private Node metadataNode;
	private int totalGeometryCount = 0;
	private boolean countSaved = false;

	// Private classes
	private class WarmUpVisitor implements SpatialIndexVisitor {

		public boolean needsToVisit(Envelope indexNodeEnvelope) {
			return true;
		}

		public void onIndexReference(Node geomNode) {
		}
	}

	/**
	 * In order to wrap one iterable or iterator in another that converts
	 * the objects from one type to another without loading all into memory,
	 * we need to use this ugly java-magic. Man, I miss Ruby right now!
	 *
	 * @author Craig
	 */
	private class IndexNodeToGeometryNodeIterable implements Iterable<Node> {

		private Iterator<Node> allIndexNodeIterator;

		private class GeometryNodeIterator implements Iterator<Node> {

			Iterator<Node> geometryNodeIterator = null;

			public boolean hasNext() {
				checkGeometryNodeIterator();
				return geometryNodeIterator != null && geometryNodeIterator.hasNext();
			}

			public Node next() {
				checkGeometryNodeIterator();
				return geometryNodeIterator == null ? null : geometryNodeIterator.next();
			}

            private void checkGeometryNodeIterator()
            {
                TraversalDescription td = database.traversalDescription()
                        .depthFirst()
                        .relationships( RTreeRelationshipTypes.RTREE_REFERENCE, Direction.OUTGOING )
                        .evaluator( Evaluators.excludeStartPosition() )
                        .evaluator( Evaluators.toDepth( 1 ) );
                while ( (geometryNodeIterator == null || !geometryNodeIterator.hasNext()) &&
                        allIndexNodeIterator.hasNext() )
                {
                    geometryNodeIterator = td.traverse( allIndexNodeIterator.next() ).nodes().iterator();
                }
            }

			public void remove() {
			}
		}

		public IndexNodeToGeometryNodeIterable(Iterable<Node> allIndexNodes) {
			this.allIndexNodeIterator = allIndexNodes.iterator();
		}

		public Iterator<Node> iterator() {
			return new GeometryNodeIterator();
		}
	}

	private class IndexNodeAreaComparator implements Comparator<Node> {

		@Override
		public int compare(Node o1, Node o2) {
			return Double.compare(getIndexNodeEnvelope(o1).getArea(), getIndexNodeEnvelope(o2).getArea());
		}
	}
}
