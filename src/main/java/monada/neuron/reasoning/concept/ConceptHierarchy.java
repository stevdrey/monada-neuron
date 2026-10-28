package monada.neuron.reasoning.concept;

import monada.neuron.reasoning.Proposition;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Host-supplied, bounded, acyclic rooted forest of labeled concepts (contract {@code ncm-wave-cognition/1} section
 * 8.1, ADR 0043).
 *
 * <p><b>Rules.</b> {@link #of} rejects (with {@link IllegalArgumentException}) any list that breaks one of them, never
 * repairing it (fixture N12):
 * <ul>
 *   <li>{@code 1..}{@link #MAX_CONCEPTS} nodes, and node {@code i} has {@code ordinal == i};</li>
 *   <li>parents precede their children: a non-root's {@code parentOrdinal} is strictly lower than its own ordinal.
 *       A self-reference or a forward reference (a cycle, or a child listed before its parent) is rejected, so every
 *       accepted list is acyclic by construction;</li>
 *   <li>a child's depth is its parent's depth plus one, below {@link #MAX_DEPTH};</li>
 *   <li>concept codes are unique.</li>
 * </ul>
 *
 * <p><b>Layout.</b> Children are kept in a compact CSR layout (one offsets array and one children array, children in
 * ascending ordinal order), and codes in a sorted array searched by binary search, so a lookup is O(log n) with no
 * hashing and no per-node collection. Construction is O(n log n) for n nodes; every array is owned and never escapes.
 */
public final class ConceptHierarchy {

    /** Largest number of concepts (contract section 11.1, {@code maxConcepts}). */
    public static final int MAX_CONCEPTS = 256;

    /** Largest number of levels: depths {@code 0..MAX_DEPTH - 1} (contract section 11.1, {@code maxDepth}). */
    public static final int MAX_DEPTH = 3;

    private final int domain;
    private final ConceptNode[] nodes;
    private final int[] childOffsets;
    private final int[] children;
    private final int[] roots;
    private final int[] leaves;
    private final int[] leafPositions;
    private final long[] sortedCodes;
    private final int[] sortedOrdinals;
    private final int levels;

    private ConceptHierarchy(int domain, ConceptNode[] nodes) {
        this.domain = domain;
        this.nodes = nodes;
        int n = nodes.length;
        int[] childCounts = new int[n];
        int rootCount = 0;
        int maxDepth = 0;
        for (ConceptNode node : nodes) {
            if (node.isRoot()) {
                rootCount++;
            } else {
                childCounts[node.parentOrdinal()]++;
            }
            maxDepth = Math.max(maxDepth, node.depth());
        }
        this.levels = maxDepth + 1;
        this.childOffsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            childOffsets[i + 1] = childOffsets[i] + childCounts[i];
        }
        this.children = new int[n - rootCount];
        this.roots = new int[rootCount];
        int[] next = Arrays.copyOf(childOffsets, n);
        int leafCount = 0;
        int r = 0;
        for (ConceptNode node : nodes) {
            if (node.isRoot()) {
                roots[r++] = node.ordinal();
            } else {
                children[next[node.parentOrdinal()]++] = node.ordinal();
            }
        }
        this.leafPositions = new int[n];
        for (int i = 0; i < n; i++) {
            leafPositions[i] = childCounts[i] == 0 ? leafCount++ : -1;
        }
        this.leaves = new int[leafCount];
        for (int i = 0; i < n; i++) {
            if (leafPositions[i] >= 0) {
                leaves[leafPositions[i]] = i;
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(nodes[a].conceptCode(), nodes[b].conceptCode()));
        this.sortedCodes = new long[n];
        this.sortedOrdinals = new int[n];
        for (int i = 0; i < n; i++) {
            sortedOrdinals[i] = order[i];
            sortedCodes[i] = nodes[order[i]].conceptCode();
            if (i > 0 && sortedCodes[i] == sortedCodes[i - 1]) {
                throw new IllegalArgumentException("concept codes must be unique, duplicate: " + sortedCodes[i]
                        + " at ordinals " + sortedOrdinals[i - 1] + " and " + sortedOrdinals[i]);
            }
        }
    }

    /**
     * Validates and builds a hierarchy.
     *
     * @param domain non-negative proposition domain of every concept
     * @param nodes the concepts, parents before children, node {@code i} with ordinal {@code i}
     * @return the hierarchy
     * @throws NullPointerException if {@code nodes} or any node is {@code null}
     * @throws IllegalArgumentException if a rule of this class is broken
     */
    public static ConceptHierarchy of(int domain, List<ConceptNode> nodes) {
        Objects.requireNonNull(nodes, "nodes must not be null");
        if (domain < 0) {
            throw new IllegalArgumentException("domain must be non-negative, got: " + domain);
        }
        if (nodes.isEmpty() || nodes.size() > MAX_CONCEPTS) {
            throw new IllegalArgumentException("a hierarchy needs 1.." + MAX_CONCEPTS + " concepts, got: "
                    + nodes.size());
        }
        ConceptNode[] copy = new ConceptNode[nodes.size()];
        for (int i = 0; i < copy.length; i++) {
            ConceptNode node = Objects.requireNonNull(nodes.get(i), "nodes must not contain null");
            if (node.ordinal() != i) {
                throw new IllegalArgumentException("node at position " + i + " has ordinal " + node.ordinal());
            }
            if (!node.isRoot()) {
                int parent = node.parentOrdinal();
                if (parent >= i) {
                    throw new IllegalArgumentException("node " + i + " references parent " + parent
                            + ", which does not precede it (cycle or child before parent)");
                }
                if (node.depth() != copy[parent].depth() + 1) {
                    throw new IllegalArgumentException("node " + i + " has depth " + node.depth()
                            + " under parent " + parent + " of depth " + copy[parent].depth());
                }
            }
            copy[i] = node;
        }
        return new ConceptHierarchy(domain, copy);
    }

    /** Returns the proposition domain of every concept. */
    public int domain() {
        return domain;
    }

    /** Returns the number of concepts. */
    public int size() {
        return nodes.length;
    }

    /** Returns the number of levels, {@code 1 + } the deepest node's depth. */
    public int levels() {
        return levels;
    }

    /**
     * Returns a node.
     *
     * @param ordinal node ordinal
     * @return the node
     * @throws IndexOutOfBoundsException if {@code ordinal} is out of range
     */
    public ConceptNode node(int ordinal) {
        return nodes[Objects.checkIndex(ordinal, nodes.length)];
    }

    /** Returns every node in ordinal order. */
    public List<ConceptNode> nodes() {
        return List.of(nodes);
    }

    /** Returns the number of children of a node. */
    public int childCount(int ordinal) {
        Objects.checkIndex(ordinal, nodes.length);
        return childOffsets[ordinal + 1] - childOffsets[ordinal];
    }

    /** Returns child {@code k} of a node, children in ascending ordinal order. */
    public int child(int ordinal, int k) {
        return children[childOffsets[ordinal] + Objects.checkIndex(k, childCount(ordinal))];
    }

    /** Returns the number of roots. */
    public int rootCount() {
        return roots.length;
    }

    /** Returns root {@code k}, roots in ascending ordinal order. */
    public int root(int k) {
        return roots[Objects.checkIndex(k, roots.length)];
    }

    /** Returns whether a node has no child. */
    public boolean isLeaf(int ordinal) {
        return leafPositions[Objects.checkIndex(ordinal, nodes.length)] >= 0;
    }

    /** Returns the number of leaves. */
    public int leafCount() {
        return leaves.length;
    }

    /** Returns the ordinal of leaf {@code position}, leaves in ascending ordinal order. */
    public int leaf(int position) {
        return leaves[Objects.checkIndex(position, leaves.length)];
    }

    /** Returns the position of a leaf among the leaves, or {@code -1} for an inner node. */
    public int leafPosition(int ordinal) {
        return leafPositions[Objects.checkIndex(ordinal, nodes.length)];
    }

    /**
     * Returns whether {@code ordinal} is {@code ancestor} or one of its descendants. O(depth).
     *
     * @param ordinal the node
     * @param ancestor the candidate ancestor
     * @return {@code true} when {@code ordinal} lies in the subtree of {@code ancestor}
     */
    public boolean inSubtree(int ordinal, int ancestor) {
        Objects.checkIndex(ancestor, nodes.length);
        int current = Objects.checkIndex(ordinal, nodes.length);
        while (current > ancestor) {
            current = nodes[current].parentOrdinal();
        }
        return current == ancestor;
    }

    /**
     * Finds a concept by its host label. O(log n), no hashing.
     *
     * @param conceptCode host label
     * @return the ordinal, or empty when the hierarchy does not contain the label
     */
    public OptionalInt ordinalOf(long conceptCode) {
        int found = Arrays.binarySearch(sortedCodes, conceptCode);
        return found >= 0 ? OptionalInt.of(sortedOrdinals[found]) : OptionalInt.empty();
    }

    /**
     * Returns the proposition a concept is emitted as (ADR 0019): {@code Proposition(domain, conceptCode)}.
     *
     * @param ordinal node ordinal
     * @return the proposition
     */
    public Proposition proposition(int ordinal) {
        return new Proposition(domain, node(ordinal).conceptCode());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConceptHierarchy that && domain == that.domain && Arrays.equals(nodes, that.nodes);
    }

    @Override
    public int hashCode() {
        return 31 * domain + Arrays.hashCode(nodes);
    }

    @Override
    public String toString() {
        return "ConceptHierarchy[domain=" + domain + ", concepts=" + nodes.length + ", levels=" + levels + "]";
    }
}
