package monada.neuron.memory;

/** Explicit outcome of one resonance-memory request. */
public enum ResonanceMemoryStatus {
    /** The adapter completed recall; an empty result list means no matches. */
    COMPLETE,

    /** The adapter or cycle retained only a deterministic result prefix. */
    PARTIAL,

    /** The memory capability is not available for this request. */
    UNAVAILABLE,

    /** The adapter reached its request timeout before producing a usable response. */
    TIMED_OUT,

    /** The adapter reported an expected recall failure without a provider-specific payload. */
    FAILED
}
