package monada.neuron.action;

import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/** Observable action-stage result retaining its typed outcome and admitted observation prefix. */
public record ActionCognitiveStageResult(ActionOutcome outcome) implements CognitiveStageResult {

    /** Requires the typed action outcome. */
    public ActionCognitiveStageResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /** Returns the fixed action position in the canonical cycle. */
    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.ACTION;
    }

    /** Expected action outcomes complete the stage without a local execution limit. */
    @Override
    public CognitiveStageStatus status() {
        return CognitiveStageStatus.COMPLETED;
    }

    /** Returns action observations in capability-defined deterministic order. */
    @Override
    public List<Signal> outputSignals() {
        return outcome.result().observations();
    }

    /** Preserves the outcome while dropping every observation rejected by the cycle budget. */
    @Override
    public ActionCognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        return new ActionCognitiveStageResult(outcome.withAdmittedObservationPrefix(admittedOutputSignals));
    }
}
