package monada.neuron.reasoning;

import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed {@code REASONING} result that retains hypotheses for {@code EVALUATION} while keeping
 * normal signal hand-off through {@link #outputSignals()}.
 *
 * @param status completion status of the stage
 * @param outputSignals ordered signals offered to the next stage
 * @param hypotheses immutable hypotheses produced this cycle
 */
public record ReasoningCognitiveStageResult(
        CognitiveStageStatus status,
        List<Signal> outputSignals,
        HypothesisSet hypotheses) implements CognitiveStageResult {

    /** Validates and snapshots components. */
    public ReasoningCognitiveStageResult {
        Objects.requireNonNull(status, "status must not be null");
        outputSignals = List.copyOf(Objects.requireNonNull(
                outputSignals,
                "outputSignals must not be null"));
        Objects.requireNonNull(hypotheses, "hypotheses must not be null");
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.REASONING;
    }

    /** Keeps hypotheses, which are independent of signals, and trims signals to the prefix. */
    @Override
    public CognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        Objects.requireNonNull(admittedOutputSignals, "admittedOutputSignals must not be null");
        if (admittedOutputSignals.size() > outputSignals.size()
                || !outputSignals.subList(0, admittedOutputSignals.size())
                        .equals(admittedOutputSignals)) {
            throw new IllegalArgumentException(
                    "admitted output signals must be a prefix of the reasoning outputs");
        }
        return new ReasoningCognitiveStageResult(status, admittedOutputSignals, hypotheses);
    }

    /** Extracts hypotheses from the previous stage result, or an empty set when it has none. */
    public static HypothesisSet hypothesesOf(Optional<CognitiveStageResult> previousResult) {
        Objects.requireNonNull(previousResult, "previousResult must not be null");
        return previousResult
                .filter(ReasoningCognitiveStageResult.class::isInstance)
                .map(result -> ((ReasoningCognitiveStageResult) result).hypotheses())
                .orElse(HypothesisSet.EMPTY);
    }
}
