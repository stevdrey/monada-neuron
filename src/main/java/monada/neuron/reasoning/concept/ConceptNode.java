package monada.neuron.reasoning.concept;

/**
 * One host-labeled concept of a {@link ConceptHierarchy} (contract {@code ncm-wave-cognition/1} section 8.1).
 *
 * <p>The label is opaque: {@code conceptCode} means only what the host declares, and Neuron never derives it from
 * spectral data. Structural rules that involve other nodes (parent order, depth consistency, unique codes) are checked
 * by {@link ConceptHierarchy#of}.
 *
 * @param ordinal stable position of the node in its hierarchy, {@code 0..ConceptHierarchy.MAX_CONCEPTS - 1}
 * @param conceptCode host label, emitted as {@code Proposition(domain, conceptCode)}
 * @param parentOrdinal ordinal of the parent, or {@link #ROOT} for a root
 * @param depth {@code 0} for a root, at most {@code ConceptHierarchy.MAX_DEPTH - 1}
 */
public record ConceptNode(int ordinal, long conceptCode, int parentOrdinal, int depth) {

    /** Parent ordinal of a root. */
    public static final int ROOT = -1;

    /** Validates the ranges that do not depend on other nodes. */
    public ConceptNode {
        if (ordinal < 0 || ordinal >= ConceptHierarchy.MAX_CONCEPTS) {
            throw new IllegalArgumentException("ordinal must be in [0, " + ConceptHierarchy.MAX_CONCEPTS
                    + "), got: " + ordinal);
        }
        if (parentOrdinal < ROOT) {
            throw new IllegalArgumentException("parentOrdinal must be -1 or a node ordinal, got: " + parentOrdinal);
        }
        if (depth < 0 || depth >= ConceptHierarchy.MAX_DEPTH) {
            throw new IllegalArgumentException("depth must be in [0, " + ConceptHierarchy.MAX_DEPTH + "), got: "
                    + depth);
        }
        if ((parentOrdinal == ROOT) != (depth == 0)) {
            throw new IllegalArgumentException("a node is a root exactly when its depth is 0, got parentOrdinal "
                    + parentOrdinal + " at depth " + depth);
        }
    }

    /** Returns whether this node is a root. */
    public boolean isRoot() {
        return parentOrdinal == ROOT;
    }
}
