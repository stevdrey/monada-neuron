package monada.neuron.runtime.state;

import monada.neuron.model.Node;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Compiled mapping from canonical Nodes to a copied dense current-state store.
 *
 * <p>Nodes are sorted by UUID to make dense indices reproducible. The snapshot copies only the
 * selected mutable values into its store. Subsequent Node transitions, energy updates, history,
 * topology, identity, and type remain exclusively in the object domain model and are not observed
 * by this experimental view.
 */
public final class NodeStateSnapshot implements AutoCloseable {

    private static final Comparator<Node> NODE_ID_ORDER = Comparator.comparing(Node::getId);

    private final Node[] nodes;
    private final NodeStateStore store;

    private NodeStateSnapshot(Node[] nodes, NodeStateStore store) {
        this.nodes = nodes;
        this.store = store;
    }

    /** Compiles an on-heap SoA state snapshot from canonical Nodes. */
    public static NodeStateSnapshot compileOnHeap(Collection<Node> sourceNodes) {
        return compile(sourceNodes, HeapNodeStateStore::new);
    }

    /** Compiles an FFM-backed state snapshot from canonical Nodes. */
    public static NodeStateSnapshot compileOffHeap(Collection<Node> sourceNodes) {
        return compile(sourceNodes, FfmNodeStateStore::new);
    }

    /** Returns the stable dense entry count. */
    public int nodeCount() {
        return nodes.length;
    }

    /** Returns whether the underlying state storage can still be accessed. */
    public boolean isOpen() {
        return store.isOpen();
    }

    /** Returns the storage without exposing any FFM implementation types. */
    public NodeStateStore stateStore() {
        NodeStateStoreSupport.requireOpen(store.isOpen());
        return store;
    }

    /**
     * Resolves a canonical Node instance to its UUID-sorted dense index.
     *
     * @throws IllegalArgumentException when the UUID is absent or belongs to another Node object
     */
    public int denseIndexOf(Node node) {
        NodeStateStoreSupport.requireOpen(store.isOpen());
        Objects.requireNonNull(node, "node must not be null");
        UUID id = node.getId();
        int low = 0;
        int high = nodes.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = nodes[middle].getId().compareTo(id);
            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else if (nodes[middle] == node) {
                return middle;
            } else {
                break;
            }
        }
        throw new IllegalArgumentException("node must be the canonical instance in this state snapshot: " + id);
    }

    /** Releases the selected state layout. The Node objects remain untouched. */
    @Override
    public void close() {
        store.close();
    }

    private static NodeStateSnapshot compile(
            Collection<Node> sourceNodes,
            NodeStateStoreFactory storeFactory) {
        Objects.requireNonNull(sourceNodes, "sourceNodes must not be null");
        Objects.requireNonNull(storeFactory, "storeFactory must not be null");
        if (sourceNodes.isEmpty()) {
            throw new IllegalArgumentException("sourceNodes must not be empty");
        }

        Node[] nodes = sourceNodes.toArray(Node[]::new);
        for (int index = 0; index < nodes.length; index++) {
            Objects.requireNonNull(nodes[index], "sourceNodes must not contain null at index " + index);
        }
        Arrays.sort(nodes, NODE_ID_ORDER);
        validateDistinctIds(nodes);

        NodeStateStore store = storeFactory.create(nodes.length);
        try {
            for (int index = 0; index < nodes.length; index++) {
                Node node = nodes[index];
                store.setState(index, node.getFrequencyState(), node.getEnergy());
            }
            return new NodeStateSnapshot(nodes, store);
        } catch (RuntimeException | Error exception) {
            store.close();
            throw exception;
        }
    }

    private static void validateDistinctIds(Node[] nodes) {
        for (int index = 1; index < nodes.length; index++) {
            if (nodes[index - 1].getId().equals(nodes[index].getId())) {
                throw new IllegalArgumentException(
                        "sourceNodes must not contain distinct Nodes with the same UUID: "
                                + nodes[index].getId());
            }
        }
    }

    @FunctionalInterface
    private interface NodeStateStoreFactory {

        NodeStateStore create(int size);
    }
}
