package monada.neuron.reasoning.concept;

/** How one {@link WaveCognitionReasoningStage} execution ended (contract {@code ncm-wave-cognition/2} section 10.1). */
public enum WaveCognitionOutcome {

    /** The cycle had no host context; nothing was resolved. */
    NO_HOST_CONTEXT,

    /** The resolver returned empty: this execution did not opt in. */
    NOT_REQUESTED,

    /** The execution opted in with no windows. */
    NO_WINDOWS,

    /** The input had more windows than {@link WaveCognitionLimits#maxWindows()}; none was processed. */
    REJECTED_OVERSIZED,

    /** Every window was processed. */
    PROCESSED,

    /** A window would exceed {@link WaveCognitionLimits#maxTotalComparisons()}; it and the rest were not processed. */
    WORK_LIMIT_REACHED,

    /**
     * The hypothesis of the last reported window did not fit {@link WaveCognitionLimits#hypothesisLimits()}; it was
     * dropped and the rest were not processed.
     */
    HYPOTHESIS_LIMIT_REACHED;

    /** Returns whether a bound cut the execution short, which the stage reports as {@code LIMIT_REACHED}. */
    public boolean limitReached() {
        return this == WORK_LIMIT_REACHED || this == HYPOTHESIS_LIMIT_REACHED;
    }

    /** Returns whether windows were examined, so the report may list them. */
    public boolean examinedWindows() {
        return this == PROCESSED || limitReached();
    }
}
