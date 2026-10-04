package monada.neuron.host;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionCognitiveStage;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.memory.ResonanceMemoryCognitiveStage;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
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
 * <p>Memory and action capabilities are optional. They are reached only through
 * {@link ResonanceMemoryPort} and {@link ActionCapability}; the runtime exposes no persistence,
 * provider, or host-domain types. The host owns input preparation and output interpretation.
 *
 * <p>Like {@link PrimaryMonad} and the cycle context, a runtime is not thread-safe: executions must
 * be sequential, and the Monad's Aeon registrations must not change while a cycle is running.
 *
 * <p>The composition is reused as-is by every execution, so a stage that captures per-cycle input
 * when it is created keeps and reapplies it each time. {@code FeedbackAdaptationCognitiveStage} is
 * the main case: it holds one prior-cycle {@code OutcomeFeedback}, and ADR 0021 makes carrying
 * feedback forward the caller's explicit choice. A feedback loop therefore builds a new runtime for
 * each cycle with the feedback derived from the previous one; construction is a single cycle and one
 * list copy, not a rebuild of Neuron.
 */
public final class NeuronRuntime {

    private final PrimaryMonad monad;
    private final DeterministicCognitiveCycle cycle;
    private final Optional<CognitiveBudget> defaultBudget;

    private NeuronRuntime(
            PrimaryMonad monad,
            DeterministicCognitiveCycle cycle,
            Optional<CognitiveBudget> defaultBudget) {
        this.monad = monad;
        this.cycle = cycle;
        this.defaultBudget = defaultBudget;
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
        var budget = defaultBudget.orElseThrow(() -> new IllegalStateException(
                "no default budget configured; pass a CognitiveBudget to execute"));
        return execute(inputSignals, budget);
    }

    /** Executes one cycle over ordered initial signals with an explicit budget for this call. */
    public CognitiveCycleResult execute(List<Signal> inputSignals, CognitiveBudget budget) {
        return cycle.execute(monad, inputSignals, budget);
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
         * hold input that is only valid for one cycle (see {@link NeuronRuntime}).
         */
        public Builder stage(CognitiveStage stage) {
            stages.add(Objects.requireNonNull(stage, "stage must not be null"));
            return this;
        }

        /** Adds the optional memory-recall stage backed by the given port and result limit. */
        public Builder memoryPort(ResonanceMemoryPort memoryPort, int maxResults) {
            return stage(new ResonanceMemoryCognitiveStage(memoryPort, maxResults));
        }

        /** Adds the optional action stage backed by the given capability and observation limit. */
        public Builder actionCapability(ActionCapability capability, int maxObservations) {
            return stage(new ActionCognitiveStage(capability, maxObservations));
        }

        /** Sets the budget used by {@link NeuronRuntime#execute(List)}. */
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
            return new NeuronRuntime(monad, cycle, Optional.ofNullable(defaultBudget));
        }
    }
}
