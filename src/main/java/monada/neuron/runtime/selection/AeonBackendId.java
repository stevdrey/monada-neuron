package monada.neuron.runtime.selection;

/**
 * Identifiers for Aeon input coordination backends.
 */
public enum AeonBackendId implements BackendId {

    /** Sequential deterministic coordination oracle (reference path). */
    DETERMINISTIC_SEQUENTIAL(true, false),

    /** Bounded parallel multi-input coordination across CPU worker pool. */
    BOUNDED_PARALLEL(false, false);

    private final boolean reference;
    private final boolean experimental;

    AeonBackendId(boolean reference, boolean experimental) {
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
        return BackendComponent.AEON_COORDINATION;
    }
}
