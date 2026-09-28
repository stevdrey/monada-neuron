package monada.neuron.runtime.selection;

/**
 * Identifiers for batch resonance scoring backends.
 */
public enum ResonanceBackendId implements BackendId {

    /** Portable scalar reference evaluator (always available, zero incubator dependencies). */
    SCALAR(true, false),

    /** Vector API SIMD batch evaluator (requires Java 26 incubator module). */
    VECTOR_API(false, false);

    private final boolean reference;
    private final boolean experimental;

    ResonanceBackendId(boolean reference, boolean experimental) {
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
        return BackendComponent.RESONANCE;
    }
}
