package monada.neuron.host;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionCognitiveStage;
import monada.neuron.aeon.Aeon;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.evolution.AdaptationPolicy;
import monada.neuron.evolution.ScopedFeedbackAdaptationCognitiveStage;
import monada.neuron.memory.ResonanceMemoryCognitiveStage;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.model.Node;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.perception.PerceptionCapability;
import monada.neuron.perception.PerceptionCognitiveStage;
import monada.neuron.signal.Signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Host-facing facade that is configured once and executes bounded cognitive cycles repeatedly.
 *
 * <p>The runtime is an integration boundary, not an orchestration layer: it owns one
 * {@link PrimaryMonad} reference and one prebuilt {@link DeterministicCognitiveCycle}, and every
 * execution delegates to that cycle. Stage ordering, termination, budget handling, and failure
 * semantics therefore remain exactly those of the cycle. The result is the cycle's own
 * {@link CognitiveCycleResult}, and operational failures propagate as
 * {@link monada.neuron.monad.CognitiveCycleException}.
 *
 * <p>Perception, memory and action capabilities are optional. They are reached only through
 * {@link PerceptionCapability}, {@link ResonanceMemoryPort} and {@link ActionCapability}; the runtime exposes no persistence,
 * provider, or host-domain types. The host owns input preparation and output interpretation.
 *
 * <p>Like {@link PrimaryMonad} and the cycle context, a runtime is not thread-safe: executions must
 * be sequential, and the Monad's Aeon registrations must not change while a cycle is running.
 *
 * <p>The composition is static and shared by every execution; cycle-local data travels in
 * {@link CycleInput}. In particular, the feedback a caller carries from one cycle to the next
 * (ADR 0021) is passed as {@link CycleInput#priorFeedback()} to a runtime configured with
 * {@link Builder#feedbackAdaptation}, so one runtime serves a whole feedback loop. A stage that
 * captures one cycle's input when it is created, such as {@code FeedbackAdaptationCognitiveStage}, still
 * reapplies it on every execution when added with {@link Builder#stage}; use
 * {@code feedbackAdaptation} for reusable runtimes.
 */
public final class NeuronRuntime {

    private final PrimaryMonad monad;
    private final DeterministicCognitiveCycle cycle;
    private final Optional<CognitiveBudget> defaultBudget;
    private final boolean consumesPriorFeedback;

    private NeuronRuntime(
            PrimaryMonad monad,
            DeterministicCognitiveCycle cycle,
            Optional<CognitiveBudget> defaultBudget,
            boolean consumesPriorFeedback) {
        this.monad = monad;
        this.cycle = cycle;
        this.defaultBudget = defaultBudget;
        this.consumesPriorFeedback = consumesPriorFeedback;
    }

    /** Starts the explicit typed composition of a runtime. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the stable cognitive identity this runtime executes for. */
    public UUID monadId() {
        return monad.getId();
    }

    /**
     * Executes one cycle with the runtime's default budget.
     *
     * @throws IllegalStateException when no default budget was configured
     */
    public CognitiveCycleResult execute(List<Signal> inputSignals) {
        return execute(CycleInput.of(inputSignals));
    }

    /** Executes one cycle over ordered initial signals with an explicit budget for this call. */
    public CognitiveCycleResult execute(List<Signal> inputSignals, CognitiveBudget budget) {
        return execute(CycleInput.of(inputSignals).withBudget(budget));
    }

    /**
     * Executes one cycle over the cycle-local {@code input}.
     *
     * <p>The budget is the input's, else the runtime default. The input's host context, if any, is
     * passed explicitly to the cycle and reaches only the perception and action requests of this execution.
     * Prior feedback is consumed by the feedback adaptation stage for this execution only and is not retained afterwards.
     *
     * <p>A runtime with a perception capability takes its signals from that capability, so the input must
     * carry none; the cycle rejects initial signals before executing anything.
     *
     * @throws IllegalStateException when no budget is available, or when prior feedback is supplied
     *         but no feedback adaptation stage is configured, because it would otherwise be ignored
     * @throws IllegalArgumentException when a perception stage is configured and the input carries
     *         initial signals
     */
    public CognitiveCycleResult execute(CycleInput input) {
        Objects.requireNonNull(input, "input must not be null");
        var budget = input.budget().or(() -> defaultBudget).orElseThrow(() -> new IllegalStateException(
                "no default budget configured; pass a CognitiveBudget with the input"));
        var feedback = input.priorFeedback();
        if (feedback.isEmpty()) {
            return cycle.execute(monad, input.signals(), budget, input.hostContext());
        }
        if (!consumesPriorFeedback) {
            throw new IllegalStateException(
                    "prior feedback supplied but no feedback adaptation stage is configured");
        }
        return ScopedFeedbackAdaptationCognitiveStage.callWith(
                feedback.get(),
                () -> cycle.execute(monad, input.signals(), budget, input.hostContext()));
    }

    /** Collects an immutable runtime composition; stages are normalized to canonical order. */
    public static final class Builder {

        private PrimaryMonad monad;
        private final List<CognitiveStage> stages = new ArrayList<>();
        private CognitiveBudget defaultBudget;

        private Builder() {
        }

        /** Sets the required Primary Monad whose canonical Aeons the stages are bound to. */
        public Builder monad(PrimaryMonad monad) {
            this.monad = Objects.requireNonNull(monad, "monad must not be null");
            return this;
        }

        /**
         * Adds any cognitive stage; its canonical position is its own {@code kind()}.
         *
         * <p>The stage instance is shared by every execution of the built runtime, so it must not
         * hold input that is only valid for one cycle (see {@link NeuronRuntime}); prior-cycle feedback
         * belongs to {@link #feedbackAdaptation}.
         */
        public Builder stage(CognitiveStage stage) {
            stages.add(Objects.requireNonNull(stage, "stage must not be null"));
            return this;
        }

        /**
         * Adds the optional perception source stage backed by the given capability and signal limit.
         *
         * <p>It occupies the single PERCEPTION position, so it excludes a PERCEPTION Aeon stage, and the
         * executions of the built runtime must supply no initial signals (ADR 0024).
         */
        public Builder perceptionCapability(PerceptionCapability capability, int maxSignals) {
            return stage(new PerceptionCognitiveStage(capability, maxSignals));
        }

        /** Adds the optional memory-recall stage backed by the given port and result limit. */
        public Builder memoryPort(ResonanceMemoryPort memoryPort, int maxResults) {
            return stage(new ResonanceMemoryCognitiveStage(memoryPort, maxResults));
        }

        /** Adds the optional action stage backed by the given capability and observation limit. */
        public Builder actionCapability(ActionCapability capability, int maxObservations) {
            return stage(new ActionCognitiveStage(capability, maxObservations));
        }

        /**
         * Adds the reusable feedback adaptation stage, configured only with its policy and targets.
         *
         * <p>It consumes the {@link CycleInput#priorFeedback()} of each execution, so a feedback loop
         * needs one runtime rather than one per cycle.
         */
        public Builder feedbackAdaptation(AdaptationPolicy policy, List<Node> targetNodes) {
            return stage(new ScopedFeedbackAdaptationCognitiveStage(policy, targetNodes));
        }

        /** Adds the reusable feedback adaptation stage targeting the member Nodes of an Aeon. */
        public Builder feedbackAdaptation(AdaptationPolicy policy, Aeon targetAeon) {
            return stage(new ScopedFeedbackAdaptationCognitiveStage(policy, targetAeon));
        }

        /** Sets the budget used when an execution supplies none. */
        public Builder defaultBudget(CognitiveBudget defaultBudget) {
            this.defaultBudget = Objects.requireNonNull(defaultBudget, "defaultBudget must not be null");
            return this;
        }

        /**
         * Builds the runtime and its single reusable cycle.
         *
         * @throws IllegalStateException when no Monad was configured
         * @throws IllegalArgumentException when two stages occupy one position or a stage is not
         *         bound to a canonical registration of the Monad
         */
        public NeuronRuntime build() {
            if (monad == null) {
                throw new IllegalStateException("a PrimaryMonad is required");
            }
            var stagePlan = List.copyOf(stages);
            var cycle = new DeterministicCognitiveCycle(stagePlan);
            for (var stage : stagePlan) {
                stage.validate(monad);
            }
            var consumesPriorFeedback = stagePlan.stream()
                    .anyMatch(ScopedFeedbackAdaptationCognitiveStage.class::isInstance);
            return new NeuronRuntime(
                    monad, cycle, Optional.ofNullable(defaultBudget), consumesPriorFeedback);
        }
    }
}
