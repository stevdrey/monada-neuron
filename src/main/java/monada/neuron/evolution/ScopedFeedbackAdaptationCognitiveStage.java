package monada.neuron.evolution;

import monada.neuron.aeon.Aeon;
import monada.neuron.context.CognitiveContext;
import monada.neuron.model.Node;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reusable {@code ADAPTATION} stage that is configured once and consumes the prior-cycle
 * {@link OutcomeFeedback} bound for the execution that is running.
 *
 * <p>{@link FeedbackAdaptationCognitiveStage} captures its feedback when it is created, so it serves
 * exactly one cycle. This stage separates static configuration (policy and targets) from cycle-local
 * data: the feedback is bound for the duration of one call with {@link #callWith}, backed by a
 * {@link ScopedValue}, and each execution delegates to a {@link FeedbackAdaptationCognitiveStage}
 * created for that feedback. Consumption, trace events, targeting, and result are therefore exactly
 * those of that stage, and the artifact is never retained after the scope ends.
 *
 * <p>When no feedback is bound the stage consumes nothing: it passes its input signals through with no
 * decisions and records no trace event, so the caller still owns any feedback it did not bind.
 *
 * <p>The current deterministic cycle executes its stages sequentially on the calling thread, which
 * is where this stage consumes the binding. Child tasks of structured concurrency may inherit scoped
 * bindings under the {@link ScopedValue} contract, but plain threads and executors do not, so the stage
 * is not meant for cycles whose stages run on such threads.
 */
public final class ScopedFeedbackAdaptationCognitiveStage implements CognitiveStage {

    private static final ScopedValue<OutcomeFeedback> PRIOR_FEEDBACK = ScopedValue.newInstance();

    private final AdaptationPolicy policy;
    private final List<Node> targetNodes;
    private final Aeon targetAeon;

    /** Creates the stage with an explicit policy and the Nodes that feedback may adapt. */
    public ScopedFeedbackAdaptationCognitiveStage(AdaptationPolicy policy, List<Node> targetNodes) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.targetNodes = List.copyOf(Objects.requireNonNull(targetNodes, "targetNodes must not be null"));
        this.targetAeon = null;
    }

    /**
     * Creates the stage targeting the member Nodes of an Aeon, resolved when the stage executes.
     *
     * <p>As with {@link FeedbackAdaptationCognitiveStage}, Aeon membership must not change while a
     * cycle executes.
     */
    public ScopedFeedbackAdaptationCognitiveStage(AdaptationPolicy policy, Aeon targetAeon) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.targetNodes = null;
        this.targetAeon = Objects.requireNonNull(targetAeon, "targetAeon must not be null");
    }

    /**
     * Runs {@code action} with {@code feedback} bound as the prior-cycle feedback, then unbinds it.
     *
     * <p>Failures thrown by {@code action} propagate unchanged.
     */
    public static <R> R callWith(OutcomeFeedback feedback, Supplier<? extends R> action) {
        Objects.requireNonNull(feedback, "feedback must not be null");
        Objects.requireNonNull(action, "action must not be null");
        return ScopedValue.where(PRIOR_FEEDBACK, feedback).call(action::get);
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.ADAPTATION;
    }

    /** Runs with no input signals only when feedback is bound, like the capturing stage. */
    @Override
    public boolean acceptsTypedOnlyHandOff() {
        return PRIOR_FEEDBACK.isBound();
    }

    /**
     * Rejects bound feedback that a different Monad produced; with no feedback bound any Monad is valid.
     *
     * @throws IllegalArgumentException if the bound feedback was produced by another Monad
     */
    @Override
    public void validate(PrimaryMonad monad) {
        Objects.requireNonNull(monad, "monad must not be null");
        if (PRIOR_FEEDBACK.isBound()) {
            delegateFor(PRIOR_FEEDBACK.get()).validate(monad);
        }
    }

    @Override
    public AdaptationCognitiveStageResult execute(
            PrimaryMonad monad,
            List<Signal> inputSignals,
            CognitiveContext context) {
        Objects.requireNonNull(monad, "monad must not be null");
        var stableInputs = List.copyOf(Objects.requireNonNull(inputSignals, "inputSignals must not be null"));
        Objects.requireNonNull(context, "context must not be null");
        if (!PRIOR_FEEDBACK.isBound()) {
            return new AdaptationCognitiveStageResult(List.of(), stableInputs);
        }
        return delegateFor(PRIOR_FEEDBACK.get()).execute(monad, stableInputs, context);
    }

    private FeedbackAdaptationCognitiveStage delegateFor(OutcomeFeedback feedback) {
        return targetAeon != null
                ? new FeedbackAdaptationCognitiveStage(policy, targetAeon, feedback)
                : new FeedbackAdaptationCognitiveStage(policy, targetNodes, feedback);
    }
}
