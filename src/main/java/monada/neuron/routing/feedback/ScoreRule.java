package monada.neuron.routing.feedback;

import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.routing.outcome.DerivedAcceptance;
import monada.neuron.routing.outcome.IneligibilityReason;
import monada.neuron.routing.outcome.QualityDimension;

import java.util.List;

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

    /**
     * True when the recorded assessment is one this neutral rule is for: the conditions {@link RoutingFeedbackPolicy}
     * uses to select it, in its precedence order (the declaration order of the neutral rules). Always false for a
     * rule that is not neutral.
     */
    public boolean covers(
            DerivedAcceptance acceptance, List<IneligibilityReason> reasons, List<QualityDimension> provenFailures) {
        boolean unattributed = reasons.contains(IneligibilityReason.NO_STAGE_ATTRIBUTION);
        return switch (this) {
            case NEUTRAL_ENVIRONMENTAL -> reasons.contains(IneligibilityReason.ENVIRONMENTAL_FAILURE);
            case NEUTRAL_CANCELLED -> reasons.contains(IneligibilityReason.CANCELLED);
            case NEUTRAL_PENDING -> reasons.contains(IneligibilityReason.PENDING);
            case NEUTRAL_IMPORTED_CLAIM -> reasons.contains(IneligibilityReason.IMPORTED_CLAIM);
            case NEUTRAL_NO_ATTRIBUTABLE_FAILURE ->
                    reasons.contains(IneligibilityReason.NO_ATTRIBUTABLE_FAILURE) && !unattributed;
            case NEUTRAL_INCOMPLETE_EVIDENCE -> !unattributed
                    && (reasons.contains(IneligibilityReason.STALE_EVALUATION_POLICY)
                            || reasons.contains(IneligibilityReason.MISSING_EVIDENCE)
                            || reasons.contains(IneligibilityReason.UNKNOWN_EVIDENCE));
            case NEUTRAL_UNVALIDATED_ACCEPTANCE ->
                    !unattributed && acceptance == DerivedAcceptance.ACCEPTED_UNVALIDATED && provenFailures.isEmpty();
            case REINFORCE_VALIDATED_STAGE_LOCAL, REINFORCE_COST_REFINED, PENALIZE_PROVEN_MANDATORY_FAILURE -> false;
        };
    }

    /**
     * The single owner of the rule/assessment consistency that both {@link RoutingFeedback} and a ledger entry's
     * {@link EntryBasis.Derived} enforce: the recorded assessment must be one {@link RoutingFeedbackPolicy} could have
     * mapped to this rule. Reasons for which the policy derives nothing never appear; a reward is validated, failure
     * free and reason free; a penalty follows an unaccepted or unvalidated result with a proven failure and none of the
     * reasons that make the policy neutral or derive nothing; a neutral rule is covered and no earlier rule is.
     *
     * @throws IllegalArgumentException if the assessment could not have selected this rule
     */
    public void requireConsistent(
            DerivedAcceptance acceptance, List<IneligibilityReason> reasons, List<QualityDimension> provenFailures) {
        if (reasons.contains(IneligibilityReason.PROVENANCE_MISMATCH)
                || reasons.contains(IneligibilityReason.STALE_ARTIFACT_REVISION)
                || reasons.contains(IneligibilityReason.CONTRADICTORY_EVIDENCE)) {
            throw new IllegalArgumentException("the policy derives nothing for reasons " + reasons);
        }
        switch (disposition) {
            case REINFORCE -> {
                if (acceptance != DerivedAcceptance.VALIDATED_ACCEPTED || !provenFailures.isEmpty()
                        || !reasons.isEmpty()) {
                    throw new IllegalArgumentException(
                            "REINFORCE needs validated acceptance, no proven failure and no ineligibility reason");
                }
            }
            case PENALIZE -> {
                if (provenFailures.isEmpty()) {
                    throw new IllegalArgumentException("PENALIZE needs a proven mandatory failure");
                }
                if (acceptance != DerivedAcceptance.NOT_ACCEPTED
                        && acceptance != DerivedAcceptance.ACCEPTED_UNVALIDATED) {
                    throw new IllegalArgumentException("PENALIZE cannot follow " + acceptance);
                }
                if (reasons.stream().anyMatch(IneligibilityReason::blocking)
                        || reasons.contains(IneligibilityReason.IMPORTED_CLAIM)
                        || reasons.contains(IneligibilityReason.NO_STAGE_ATTRIBUTION)
                        || reasons.contains(IneligibilityReason.NO_ATTRIBUTABLE_FAILURE)) {
                    throw new IllegalArgumentException(
                            "PENALIZE must not carry a reason that blocks, makes the policy neutral or derives nothing: "
                                    + reasons);
                }
            }
            case NEUTRAL -> {
                if (!covers(acceptance, reasons, provenFailures)) {
                    throw new IllegalArgumentException(
                            "rule " + this + " does not match the recorded assessment: " + acceptance + " " + reasons);
                }
                for (ScoreRule earlier : values()) {
                    if (earlier.ordinal() < ordinal() && earlier.covers(acceptance, reasons, provenFailures)) {
                        throw new IllegalArgumentException(
                                "rule " + earlier + " takes precedence over " + this + " for " + reasons);
                    }
                }
            }
        }
    }

    /** Disposition this rule yields. */
    public FeedbackDisposition disposition() {
        return disposition;
    }
}
