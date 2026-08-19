package monada.neuron.action;

/** Explicit expected outcome of one action-capability execution. */
public enum ActionStatus {
    /** The capability completed the requested action. */
    SUCCEEDED,

    /** The capability or cycle retained only a deterministic observable prefix. */
    PARTIALLY_COMPLETED,

    /** The capability declined the requested action without executing it. */
    REJECTED,

    /** The capability was unavailable for this request. */
    UNAVAILABLE,

    /** The capability reached its request timeout before producing a usable result. */
    TIMED_OUT,

    /** The capability reported an expected execution failure without provider-specific payloads. */
    FAILED
}
