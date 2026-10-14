package monada.neuron.routing.feedback;

import monada.neuron.evolution.FeedbackDisposition;

/** The explicit rule that produced a feedback's disposition and scalar; one per derived feedback. */
public enum ScoreRule {
    /** Validated acceptance attributable to the executed route's own stage: {@code reinforceScore}. */
    REINFORCE_VALIDATED_STAGE_LOCAL(FeedbackDisposition.REINFORCE),
    /** As above, and a directly comparable validated execution was strictly worse on the declared objective. */
    REINFORCE_COST_REFINED(FeedbackDisposition.REINFORCE),
    /** A proven effective mandatory failure attributable to the executed route's stage: {@code penalizeScore}. */
    PENALIZE_PROVEN_MANDATORY_FAILURE(FeedbackDisposition.PENALIZE),
    /** The worker was unavailable or timed out: says nothing about the route. */
    NEUTRAL_ENVIRONMENTAL(FeedbackDisposition.NEUTRAL),
    /** The invocation or the outcome was cancelled. */
    NEUTRAL_CANCELLED(FeedbackDisposition.NEUTRAL),
    /** No current outcome, or the outcome is pending. */
    NEUTRAL_PENDING(FeedbackDisposition.NEUTRAL),
    /** The outcome is an imported claim, never rewarded and never penalized. */
    NEUTRAL_IMPORTED_CLAIM(FeedbackDisposition.NEUTRAL),
    /** Not accepted and no proven mandatory failure: a refusal or unjudged failure is not a failure of the route. */
    NEUTRAL_NO_ATTRIBUTABLE_FAILURE(FeedbackDisposition.NEUTRAL),
    /** Mandatory evidence is missing, unknown or was judged under another evaluation policy. */
    NEUTRAL_INCOMPLETE_EVIDENCE(FeedbackDisposition.NEUTRAL),
    /** Accepted but not validated for a reason other than evidence, for example an unfinished accepted attempt. */
    NEUTRAL_UNVALIDATED_ACCEPTANCE(FeedbackDisposition.NEUTRAL);

    private final FeedbackDisposition disposition;

    ScoreRule(FeedbackDisposition disposition) {
        this.disposition = disposition;
    }

    /** Disposition this rule yields. */
    public FeedbackDisposition disposition() {
        return disposition;
    }
}
