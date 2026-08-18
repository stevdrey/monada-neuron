package monada.neuron.monad;

import monada.neuron.context.CognitiveContext;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

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
}
