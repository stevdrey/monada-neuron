package monada.neuron.runtime.selection;

/**
 * Identifiers for internal node numeric state storage layouts.
 */
public enum StateBackendId implements BackendId {

    /** Standard on-heap object representation in canonical Node instances (reference). */
    HEAP_OBJECT(true, false),

    /** On-heap contiguous primitive Structure-of-Arrays layout (double[]). */
    HEAP_SOA(false, false),

    /** Experimental off-heap native memory segment layout via FFM (evaluation-only, not promoted). */
    FFM_OFF_HEAP(false, true);

    private final boolean reference;
    private final boolean experimental;

    StateBackendId(boolean reference, boolean experimental) {
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
        return BackendComponent.NODE_STATE;
    }
}
