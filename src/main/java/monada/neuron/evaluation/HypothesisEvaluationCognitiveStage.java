package monada.neuron.evaluation;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.ReasoningCognitiveStageResult;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical {@code EVALUATION} stage: scores the hypotheses produced by the preceding
 * {@code REASONING} stage and selects a bounded ranked subset through a
 * {@link HypothesisEvaluationPolicy}.
 *
 * <p>The stage observes hypotheses only. It never mutates Nodes, adaptation state, or the context
 * beyond what the cycle records, and it passes its input signals through so later stages still run.
 * It runs even when {@code REASONING} produced hypotheses but no output signals.
 */
public final class HypothesisEvaluationCognitiveStage implements CognitiveStage {

    private final HypothesisEvaluationPolicy policy;
    private final int maxSelected;

    /** Creates an evaluation stage with an explicit policy and positive selection bound. */
    public HypothesisEvaluationCognitiveStage(HypothesisEvaluationPolicy policy, int maxSelected) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        if (maxSelected <= 0) {
            throw new IllegalArgumentException("maxSelected must be positive, got: " + maxSelected);
        }
        this.maxSelected = maxSelected;
    }

    /** Returns the fixed evaluation position in the canonical cycle. */
    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.EVALUATION;
    }

    /** Allows evaluation to run when reasoning produced hypotheses but no output signals. */
    @Override
    public boolean acceptsTypedOnlyHandOff() {
        return true;
    }

    /** Evaluates an empty candidate set because no previous result is available. */
    @Override
    public CognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveContext context) {
        return execute(monad, inputSignals, Optional.empty(), context);
    }

    /** Evaluates the hypotheses carried by the previous {@code REASONING} result. */
    @Override
    public CognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            Optional<CognitiveStageResult> previousResult,
            CognitiveContext context) {
        Objects.requireNonNull(monad, "monad must not be null");
        var stableInputs = List.copyOf(Objects.requireNonNull(
                inputSignals,
                "inputSignals must not be null"));
        Objects.requireNonNull(context, "context must not be null");
        var hypotheses = ReasoningCognitiveStageResult.hypothesesOf(previousResult);
        var evaluation = Objects.requireNonNull(
                policy.evaluate(hypotheses, maxSelected),
                "policy evaluation must not be null");
        return new EvaluationCognitiveStageResult(
                CognitiveStageStatus.COMPLETED,
                stableInputs,
                hypotheses,
                evaluation);
    }
}
