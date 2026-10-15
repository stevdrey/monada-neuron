package monada.neuron.evaluation.routing;

import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.feedback.ScoreRule;
import monada.neuron.routing.outcome.EligibilityVerdict;
import monada.neuron.routing.outcome.StageAttribution;

import java.util.Objects;
import java.util.Optional;

/**
 * One observation delivered to the feedback policy, with what its raw evidence admits as credit to audit it against.
 *
 * <p>The {@link #verdict} is the {@code OutcomeEvaluator}'s, the same assessment the feedback policy acts on, so it
 * is reported for consistency, not as an independent judgement. The independent part of the audit is
 * {@link #rewardEvidence} and {@link #penaltyEvidence}, read directly from the observation's fields
 * ({@link RawEvidence}), and the credited cohort, compared whole with the answered decision's own.
 *
 * @param experienceRef sample reference
 * @param revision sample revision
 * @param ledgerSequence when it became visible
 * @param decisionRef the decision it answers
 * @param stageKind the routed stage kind
 * @param executedRoute the route that actually executed
 * @param decisionBucket the cohort bucket the answered decision recorded; credit must land in this bucket
 * @param replayed whether it answers a replayed decision (synthetic) rather than a logged one (export)
 * @param verdict {@code OutcomeEvaluator} verdict, the assessment the feedback policy also acts on
 * @param attribution the outcome's stage attribution
 * @param rewardEvidence whether the observation's raw fields admit a reward
 * @param penaltyEvidence whether the observation's raw fields admit a penalty
 * @param rule the derived score rule, if feedback was derived
 * @param noUpdate why nothing was derived, otherwise
 * @param credited the cohort the derived feedback credits, if it carries an entry
 * @param update what the state did with it
 */
public record FeedbackEvent(
        String experienceRef,
        long revision,
        long ledgerSequence,
        DecisionRef decisionRef,
        String stageKind,
        RouteKey executedRoute,
        String decisionBucket,
        boolean replayed,
        EligibilityVerdict verdict,
        StageAttribution.Basis attribution,
        boolean rewardEvidence,
        boolean penaltyEvidence,
        Optional<ScoreRule> rule,
        Optional<NoUpdateReason> noUpdate,
        Optional<CohortKey> credited,
        Update update) {

    /** What the state did with a derivation. */
    public enum Update {
        /** Derived only; this arm never consumes. */
        NOT_CONSUMED,
        APPLIED,
        RECORDED,
        UNCHANGED,
        /** A correction required a rebuild, which was staged and swapped in. */
        REBUILT,
        BLOCKED,
        FOREIGN_PARTITION
    }

    /** Validates the fields. */
    public FeedbackEvent {
        Objects.requireNonNull(experienceRef, "experienceRef must not be null");
        Objects.requireNonNull(decisionRef, "decisionRef must not be null");
        Objects.requireNonNull(stageKind, "stageKind must not be null");
        Objects.requireNonNull(executedRoute, "executedRoute must not be null");
        Objects.requireNonNull(decisionBucket, "decisionBucket must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(attribution, "attribution must not be null");
        Objects.requireNonNull(rule, "rule must not be null");
        Objects.requireNonNull(noUpdate, "noUpdate must not be null");
        Objects.requireNonNull(credited, "credited must not be null");
        Objects.requireNonNull(update, "update must not be null");
        if (rule.isPresent() == noUpdate.isPresent()) {
            throw new IllegalArgumentException("exactly one of rule and noUpdate is present");
        }
    }

    /**
     * Whether the derivation breaks the quality-gated credit rules (ADR 0029): a reward the raw evidence does not
     * admit or without a positive, stage-local candidate, a penalty likewise, or credit for any cohort other than the
     * answered decision's (scope, stage kind, cohort bucket and executed route).
     */
    public boolean eligibilityViolation() {
        if (rule.isEmpty()) {
            return false;
        }
        FeedbackDisposition disposition = rule.get().disposition();
        // the whole key is compared, so a component added to CohortKey can never escape the audit
        boolean wrongTarget = credited.isPresent() && !credited.get().equals(
                new CohortKey(decisionRef.scopeId(), stageKind, decisionBucket, executedRoute));
        boolean untargeted = attribution != StageAttribution.Basis.STAGE_LOCAL || credited.isEmpty() || wrongTarget;
        return switch (disposition) {
            case REINFORCE -> !rewardEvidence || verdict != EligibilityVerdict.POSITIVE_CANDIDATE || untargeted;
            case PENALIZE -> !penaltyEvidence || verdict != EligibilityVerdict.NEGATIVE_CANDIDATE || untargeted;
            case NEUTRAL -> credited.isPresent();
        };
    }

    /** Whether the derivation credits a cohort. */
    public boolean credits() {
        return credited.isPresent();
    }
}
