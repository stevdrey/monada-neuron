package monada.neuron.evolution;

import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result of the ADAPTATION cognitive stage, retaining evaluated decisions and admitted output signals.
 */
public record AdaptationCognitiveStageResult(
        List<AdaptationDecision> decisions,
        List<Signal> outputSignals) implements CognitiveStageResult {

    /** Copies and validates immutable lists of decisions and output signals. */
    public AdaptationCognitiveStageResult {
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions must not be null"));
        outputSignals = List.copyOf(Objects.requireNonNull(outputSignals, "outputSignals must not be null"));
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.ADAPTATION;
    }

    @Override
    public CognitiveStageStatus status() {
        return CognitiveStageStatus.COMPLETED;
    }

    @Override
    public List<Signal> outputSignals() {
        return outputSignals;
    }

    @Override
    public AdaptationCognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        return new AdaptationCognitiveStageResult(decisions, admittedOutputSignals);
    }
}
