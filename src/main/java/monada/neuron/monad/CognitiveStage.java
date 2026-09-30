package monada.neuron.monad;

import monada.neuron.context.CognitiveContext;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed extension point for one optional position in the native cognitive cycle.
 *
 * <p>Implementations must leave the supplied context active. Memory, evaluation, adaptation,
 * and action integrations can add focused stage implementations without changing cycle ordering.
 */
public interface CognitiveStage {

    /** Returns the fixed canonical position occupied by this stage. */
    CognitiveStageKind kind();

    /** Validates Monad-specific bindings before the cycle creates or mutates its context. */
    default void validate(PrimaryMonad monad) {
        Objects.requireNonNull(monad, "monad must not be null");
    }

    /** Executes this stage with signals in deterministic hand-off order. */
    CognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveContext context);

    /**
     * Executes this stage with the cycle-normalized result of the preceding executed stage.
     *
     * <p>The default ignores {@code previousResult}, so stages that only consume signals need no
     * change. Stages that consume typed upstream artifacts, such as hypotheses, override this.
     */
    default CognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            Optional<CognitiveStageResult> previousResult,
            CognitiveContext context) {
        return execute(monad, inputSignals, context);
    }
}
