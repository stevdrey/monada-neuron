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
 * Typed {@code EVALUATION} result: the ranked evaluation, which carries the candidate set it scored,
 * next to the normal signal hand-off. Hypotheses are never converted into maps or signals.
 *
 * @param status completion status of the stage
 * @param outputSignals ordered signals offered to the next stage
 * @param evaluation ranked selection with per-candidate score breakdowns and the scored set
 */
public record EvaluationCognitiveStageResult(
        CognitiveStageStatus status,
        List<Signal> outputSignals,
        HypothesisEvaluation evaluation) implements CognitiveStageResult {

    /** Validates and snapshots components. */
    public EvaluationCognitiveStageResult {
        Objects.requireNonNull(status, "status must not be null");
        outputSignals = List.copyOf(Objects.requireNonNull(
                outputSignals,
                "outputSignals must not be null"));
        Objects.requireNonNull(evaluation, "evaluation must not be null");
    }

    /** Returns the candidate set the evaluation scored; selected sequences resolve in it. */
    public HypothesisSet evaluated() {
        return evaluation.evaluated();
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
        return new EvaluationCognitiveStageResult(status, admittedOutputSignals, evaluation);
    }

    /**
     * Rejects signal evidence in the evaluated set that references an occurrence the context never
     * accepted, so a custom {@code EVALUATION} stage cannot bypass the provenance rule of ADR 0019.
     */
    @Override
    public void validateProvenance(CognitiveContext context) {
        Objects.requireNonNull(context, "context must not be null");
        evaluated().validateSignalProvenance(context.acceptedSignals());
    }
}
