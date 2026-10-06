package monada.neuron.perception;

/**
 * Explicit expected outcome of one perception-capability execution, as reported by the adapter.
 *
 * <p>These values describe what the adapter achieved, never what the cognitive cycle admitted; see
 * {@code ObservationAdmission} for budget truncation.
 */
public enum PerceptionStatus {
    /** The adapter resolved the observation and produced at least one signal. */
    SUCCEEDED,

    /** The adapter resolved the observation, which legitimately yielded no signals. */
    EMPTY,

    /** The adapter reported that it observed only part of the requested input; it still produced signals. */
    PARTIALLY_COMPLETED,

    /** The adapter declined the request, for example because the host context is missing or unknown. */
    REJECTED,

    /** The adapter was unavailable for this request. */
    UNAVAILABLE,

    /** The adapter reached its own timeout before producing a usable result. */
    TIMED_OUT,

    /** The adapter reported an expected failure without provider-specific payloads. */
    FAILED
}
