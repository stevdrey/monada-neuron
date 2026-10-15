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
 * One observation delivered to the feedback policy, with an independent learning assessment to audit it against.
 *
 * @param experienceRef sample reference
 * @param revision sample revision
 * @param ledgerSequence when it became visible
 * @param decisionRef the decision it answers
 * @param stageKind the routed stage kind
 * @param executedRoute the route that actually executed
 * @param replayed whether it answers a replayed decision (synthetic) rather than a logged one (export)
 * @param verdict {@code OutcomeEvaluator} verdict, computed independently of the feedback policy
 * @param attribution the outcome's stage attribution
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
        boolean replayed,
        EligibilityVerdict verdict,
        StageAttribution.Basis attribution,
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
     * Whether the derivation breaks the quality-gated credit rules (ADR 0029): a reward without a positive, stage-local
     * candidate, a penalty without a negative candidate, or credit for a route or stage other than the executed one.
     */
    public boolean eligibilityViolation() {
        if (rule.isEmpty()) {
            return false;
        }
        FeedbackDisposition disposition = rule.get().disposition();
        boolean wrongTarget = credited.isPresent() && (!credited.get().route().equals(executedRoute)
                || !credited.get().stageKind().equals(stageKind)
                || !credited.get().scopeId().equals(decisionRef.scopeId()));
        return switch (disposition) {
            case REINFORCE -> verdict != EligibilityVerdict.POSITIVE_CANDIDATE
                    || attribution != StageAttribution.Basis.STAGE_LOCAL || credited.isEmpty() || wrongTarget;
            case PENALIZE -> verdict != EligibilityVerdict.NEGATIVE_CANDIDATE
                    || attribution != StageAttribution.Basis.STAGE_LOCAL || credited.isEmpty() || wrongTarget;
            case NEUTRAL -> credited.isPresent();
        };
    }

    /** Whether the derivation credits a cohort. */
    public boolean credits() {
        return credited.isPresent();
    }
}
