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

    /**
     * Returns whether this stage can execute with zero input signals when the previous result
     * retains a typed artifact (see {@link CognitiveStageResult#retainsTypedHandOff()}).
     *
     * <p>Stages that require signals keep the default and the cycle ends with {@code NO_SIGNALS}.
     */
    default boolean acceptsTypedOnlyHandOff() {
        return false;
    }

    /**
     * Returns whether this stage produces signals from the host instead of consuming a predecessor's.
     *
     * <p>The deterministic cycle owns the rules: a source must be the first configured stage, runs with
     * an empty initial batch, and a cycle with a source stage rejects non-empty initial signals before
     * executing anything. Stages that consume signals keep the default.
     */
    default boolean isSource() {
        return false;
    }
}
