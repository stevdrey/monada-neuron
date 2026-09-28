package monada.neuron.runtime.selection;

/**
 * Strategy defining behavior when a requested or specialized backend is unavailable or semantically ineligible.
 */
public enum FallbackPolicy {

    /**
     * Fall back cleanly to the reference implementation while recording the diagnostic reason.
     */
    FALLBACK_TO_REFERENCE,

    /**
     * Fail immediately with an explicit exception rather than falling back or guessing.
     */
    FAIL_FAST
}
