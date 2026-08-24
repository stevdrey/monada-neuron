package monada.neuron.runtime.graph;

import monada.neuron.model.Node;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable compiled view of a stable directed {@link Node} topology.
 *
 * <p>The snapshot maps canonical Nodes sorted by UUID to dense internal indices and stores their
 * adjacency in compressed sparse row (CSR) arrays. It remains a runtime view: Node identity and
 * mutable state stay owned by the object domain model. A caller must compile a new snapshot after
 * a structural Node mutation.
 *
 * <p>This class is not a concurrency boundary. Node topology remains sequential and callers must
 * not mutate it while compilation or propagation is in progress.
 */
public final class CompactGraphSnapshot {

    private static final Comparator<Node> NODE_ID_ORDER = Comparator.comparing(Node::getId);

    private final Node[] nodes;
    private final long[] topologyVersions;
    private final int[] offsets;
    private final int[] targets;

    private CompactGraphSnapshot(
            Node[] nodes,
            long[] topologyVersions,
            int[] offsets,
            int[] targets) {
        this.nodes = nodes;
        this.topologyVersions = topologyVersions;
        this.offsets = offsets;
        this.targets = targets;
    }

    /**
     * Compiles a canonical, closed Node collection into a CSR topology snapshot.
     *
     * @param sourceNodes every Node reachable through an included connection; Nodes are ordered by
     *                    UUID, independently of collection order
     * @return immutable compact topology view
     * @throws IllegalArgumentException when the collection is empty, has duplicate UUIDs, or an
     *                                  edge refers to a non-canonical Node outside the collection
     * @throws IllegalStateException when topology changes while the snapshot is being compiled
     */
    public static CompactGraphSnapshot compile(Collection<Node> sourceNodes) {
        Objects.requireNonNull(sourceNodes, "sourceNodes must not be null");
        if (sourceNodes.isEmpty()) {
            throw new IllegalArgumentException("sourceNodes must not be empty");
        }

        Node[] nodes = sourceNodes.toArray(Node[]::new);
        for (int index = 0; index < nodes.length; index++) {
            Objects.requireNonNull(nodes[index], "sourceNodes must not contain null at index " + index);
        }
        Arrays.sort(nodes, NODE_ID_ORDER);
        validateDistinctIds(nodes);

        long[] topologyVersions = captureTopologyVersions(nodes);
        Map<UUID, Integer> indicesById = indexNodes(nodes);
        int[][] rows = compileRows(nodes, indicesById);
        int[] offsets = createOffsets(rows);
        int[] targets = flatten(rows, offsets[nodes.length]);

        if (!versionsMatch(nodes, topologyVersions)) {
            throw new IllegalStateException("node topology changed while compiling compact graph snapshot");
        }
        return new CompactGraphSnapshot(nodes, topologyVersions, offsets, targets);
    }

    /** Returns the number of canonical Nodes in this compiled topology. */
    public int nodeCount() {
        return nodes.length;
    }

    /** Returns the number of directed CSR adjacency entries in this compiled topology. */
    public int edgeCount() {
        return targets.length;
    }

    /**
     * Returns whether every canonical Node still has the connection-set version captured here.
     *
     * <p>This check intentionally excludes Node frequency-state and energy transitions because
     * the compact engine continues to expose the live Node instances to processors and policies.
     */
    public boolean isCurrent() {
        return versionsMatch(nodes, topologyVersions);
    }

    void requireCurrent() {
        if (!isCurrent()) {
            throw new IllegalStateException(
                    "compact graph snapshot is stale; recompile after topology mutation");
        }
    }

    int requireCanonicalIndex(Node node) {
        Objects.requireNonNull(node, "startNode must not be null");
        int low = 0;
        int high = nodes.length - 1;
        UUID id = node.getId();
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
        throw new IllegalArgumentException(
                "startNode must be the canonical instance in the compact graph snapshot: " + id);
    }

    Node nodeAt(int index) {
        return nodes[index];
    }

    int firstTargetOffset(int sourceIndex) {
        return offsets[sourceIndex];
    }

    int targetLimitOffset(int sourceIndex) {
        return offsets[sourceIndex + 1];
    }

    int targetAt(int targetOffset) {
        return targets[targetOffset];
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

    private static long[] captureTopologyVersions(Node[] nodes) {
        long[] versions = new long[nodes.length];
        for (int index = 0; index < nodes.length; index++) {
            versions[index] = nodes[index].getTopologyVersion();
        }
        return versions;
    }

    private static Map<UUID, Integer> indexNodes(Node[] nodes) {
        var indicesById = new HashMap<UUID, Integer>(nodes.length);
        for (int index = 0; index < nodes.length; index++) {
            indicesById.put(nodes[index].getId(), index);
        }
        return indicesById;
    }

    private static int[][] compileRows(Node[] nodes, Map<UUID, Integer> indicesById) {
        int[][] rows = new int[nodes.length][];
        for (int sourceIndex = 0; sourceIndex < nodes.length; sourceIndex++) {
            Node[] connections = nodes[sourceIndex].getConnections().toArray(Node[]::new);
            int[] row = new int[connections.length];
            for (int targetPosition = 0; targetPosition < connections.length; targetPosition++) {
                Node target = connections[targetPosition];
                Integer targetIndex = indicesById.get(target.getId());
                if (targetIndex == null || nodes[targetIndex] != target) {
                    throw new IllegalArgumentException(
                            "connection target must be a canonical sourceNodes instance: "
                                    + target.getId());
                }
                row[targetPosition] = targetIndex;
            }
            Arrays.sort(row);
            rows[sourceIndex] = row;
        }
        return rows;
    }

    private static int[] createOffsets(int[][] rows) {
        int[] offsets = new int[rows.length + 1];
        int totalEdges = 0;
        for (int index = 0; index < rows.length; index++) {
            offsets[index] = totalEdges;
            try {
                totalEdges = Math.addExact(totalEdges, rows[index].length);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("compact graph has too many edges", exception);
            }
        }
        offsets[rows.length] = totalEdges;
        return offsets;
    }

    private static int[] flatten(int[][] rows, int totalEdges) {
        int[] targets = new int[totalEdges];
        int targetOffset = 0;
        for (int[] row : rows) {
            System.arraycopy(row, 0, targets, targetOffset, row.length);
            targetOffset += row.length;
        }
        return targets;
    }

    private static boolean versionsMatch(Node[] nodes, long[] topologyVersions) {
        for (int index = 0; index < nodes.length; index++) {
            if (nodes[index].getTopologyVersion() != topologyVersions[index]) {
                return false;
            }
        }
        return true;
    }
}
