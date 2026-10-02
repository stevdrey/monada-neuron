package monada.neuron.evaluation;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Typed {@code EVALUATION} result: the evaluated candidate set and its ranked evaluation, next to
 * the normal signal hand-off. Hypotheses are never converted into maps or signals.
 *
 * @param status completion status of the stage
 * @param outputSignals ordered signals offered to the next stage
 * @param evaluated immutable candidate set that was scored; selected sequences resolve in it
 * @param evaluation ranked selection with per-candidate score breakdowns
 */
public record EvaluationCognitiveStageResult(
        CognitiveStageStatus status,
        List<Signal> outputSignals,
        HypothesisSet evaluated,
        HypothesisEvaluation evaluation) implements CognitiveStageResult {

    /** Validates components and that the evaluation describes the evaluated set. */
    public EvaluationCognitiveStageResult {
        Objects.requireNonNull(status, "status must not be null");
        outputSignals = List.copyOf(Objects.requireNonNull(
                outputSignals,
                "outputSignals must not be null"));
        Objects.requireNonNull(evaluated, "evaluated must not be null");
        Objects.requireNonNull(evaluation, "evaluation must not be null");
        if (evaluation.evaluatedCount() != evaluated.size()) {
            throw new IllegalArgumentException(
                    "evaluation covers " + evaluation.evaluatedCount()
                            + " candidates but the evaluated set has " + evaluated.size());
        }
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.EVALUATION;
    }

    /** Keeps the typed evaluation, which is independent of signals, and trims signals to the prefix. */
    @Override
    public CognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        Objects.requireNonNull(admittedOutputSignals, "admittedOutputSignals must not be null");
        if (admittedOutputSignals.size() > outputSignals.size()
                || !outputSignals.subList(0, admittedOutputSignals.size())
                        .equals(admittedOutputSignals)) {
            throw new IllegalArgumentException(
                    "admitted output signals must be a prefix of the evaluation outputs");
        }
        return new EvaluationCognitiveStageResult(status, admittedOutputSignals, evaluated, evaluation);
    }

    /**
     * Rejects signal evidence in the evaluated set that references an occurrence the context never
     * accepted, so a custom {@code EVALUATION} stage cannot bypass the provenance rule of ADR 0019.
     */
    @Override
    public void validateProvenance(CognitiveContext context) {
        Objects.requireNonNull(context, "context must not be null");
        evaluated.validateSignalProvenance(context.acceptedSignals());
    }
}
