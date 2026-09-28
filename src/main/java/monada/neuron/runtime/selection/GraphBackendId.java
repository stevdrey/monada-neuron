package monada.neuron.runtime.selection;

/**
 * Identifiers for signal propagation graph execution backends.
 */
public enum GraphBackendId implements BackendId {

    /** Deterministic breadth-first traversal over object graph model (reference oracle). */
    DETERMINISTIC_OBJECT(true, false),

    /** Compact CSR array traversal view requiring compiled immutable snapshot. */
    COMPACT_CSR(false, false);

    private final boolean reference;
    private final boolean experimental;

    GraphBackendId(boolean reference, boolean experimental) {
        this.reference = reference;
        this.experimental = experimental;
    }

    @Override
    public boolean isReference() {
        return reference;
    }

    @Override
    public boolean isExperimental() {
        return experimental;
    }

    @Override
    public BackendComponent component() {
        return BackendComponent.GRAPH_PROPAGATION;
    }
}
