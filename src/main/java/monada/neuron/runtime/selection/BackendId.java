package monada.neuron.runtime.selection;

/**
 * Common strongly typed contract for runtime backend identifiers.
 */
public sealed interface BackendId permits ResonanceBackendId, GraphBackendId, AeonBackendId, StateBackendId {

    /** Returns the programmatic name of this backend. */
    String name();

    /** Returns whether this backend is the portable reference oracle implementation. */
    boolean isReference();

    /** Returns whether this backend is experimental or evaluation-only. */
    boolean isExperimental();

    /** Returns the cognitive component governed by this backend identifier. */
    BackendComponent component();
}
