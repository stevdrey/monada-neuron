package monada.neuron.evaluation.routing;

import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.EvidencePolicy;
import monada.neuron.routing.outcome.InvocationStatus;
import monada.neuron.routing.outcome.OutcomeOrigin;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityObservation;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.RoutingOutcome;
import monada.neuron.routing.outcome.StageAttribution;

/**
 * What an observation's own fields, read directly and not through {@code OutcomeEvaluator}, admit as credit: the
 * necessary conditions of ADR 0029 and the routing contract that a reward or a penalty must meet, so the eligibility
 * audit does not merely repeat the verdict the feedback policy itself acted on.
 *
 * <p>A reward needs a validated acceptance of the routed attempt itself ({@code STAGE_LOCAL}, the accepted attempt
 * known and equal to the routed one, which the contract requires of a stage-local attribution), a completed
 * invocation, and a passing (or not applicable) latest judgement of every mandatory dimension on that attempt. A
 * penalty needs a stage-local, validated, settled outcome (not pending or cancelled) of an invocation that neither was
 * cancelled nor failed environmentally, and a mandatory dimension whose latest judgement on the routed attempt fails.
 * These are necessary, not sufficient: the evaluator may still refuse credit for reasons such as stale provenance.
 *
 * @param reward whether a reward is admissible
 * @param penalty whether a penalty is admissible
 */
record RawEvidence(boolean reward, boolean penalty) {

    /** Reads the conditions from an observed outcome under the evidence policy in force. */
    static RawEvidence of(RoutingOutcome outcome, EvidencePolicy policy) {
        String routed = outcome.decisionRef().attemptId();
        boolean stageLocal = outcome.attribution().basis() == StageAttribution.Basis.STAGE_LOCAL;
        boolean validated = outcome.outcomeOrigin() == OutcomeOrigin.VALIDATED;
        boolean allPass = true;
        boolean anyFail = false;
        for (QualityDimension dimension : policy.mandatoryDimensions()) {
            QualityState latest = latest(outcome, dimension, routed);
            allPass &= latest == QualityState.PASS || latest == QualityState.NOT_APPLICABLE;
            anyFail |= latest == QualityState.FAIL;
        }
        boolean acceptedRouted = outcome.acceptedAttemptId() instanceof Feature.Known<String> accepted
                && accepted.value().equals(routed);
        boolean reward = stageLocal && validated && outcome.outcomeStatus() == OutcomeStatus.ACCEPTED && acceptedRouted
                && outcome.invocation() == InvocationStatus.COMPLETED && allPass;
        boolean penalty = stageLocal && validated && outcome.outcomeStatus() != OutcomeStatus.PENDING
                && outcome.outcomeStatus() != OutcomeStatus.CANCELLED
                && outcome.invocation() != InvocationStatus.CANCELLED && !outcome.invocation().environmental()
                && anyFail;
        return new RawEvidence(reward, penalty);
    }

    /** The latest judgement of a dimension on an attempt visible at the outcome's cutoff, or {@code null}. */
    private static QualityState latest(RoutingOutcome outcome, QualityDimension dimension, String attempt) {
        QualityObservation latest = null;
        for (QualityObservation observation : outcome.observations()) {
            if (observation.dimension() == dimension && observation.attemptId().equals(attempt)
                    && observation.ledgerSequence() <= outcome.cutoff()
                    && (latest == null || observation.ledgerSequence() > latest.ledgerSequence())) {
                latest = observation;
            }
        }
        return latest == null ? null : latest.state();
    }
}
