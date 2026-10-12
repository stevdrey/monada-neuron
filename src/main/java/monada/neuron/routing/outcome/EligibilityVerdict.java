package monada.neuron.routing.outcome;

/**
 * Whether an observation can support routing feedback. It is not a score and not a feedback disposition; #65 maps
 * the verdict and the reasons to {@code REINFORCE}, {@code PENALIZE} or {@code NEUTRAL}.
 */
public enum EligibilityVerdict {
    /** Validated acceptance attributable to the executed route's stage. */
    POSITIVE_CANDIDATE,
    /** A proven mandatory gate failure attributable to the executed route's stage. */
    NEGATIVE_CANDIDATE,
    /** Supports neither; see the reasons. */
    NOT_ELIGIBLE
}
