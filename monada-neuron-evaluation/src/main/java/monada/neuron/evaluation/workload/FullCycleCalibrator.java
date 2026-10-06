package monada.neuron.evaluation.workload;

import monada.neuron.action.ActionCapability;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.GraphTopology;
import monada.neuron.evolution.AdaptationPolicy;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Picks the smallest bound of {@link DeterministicWorkloadGenerator#FULL_CYCLE_PROPAGATION_LADDER} under which
 * the full-cycle workload of one seed really executes all five stages.
 *
 * <p>Each rung runs on freshly generated topologies because the adaptation stage mutates nodes. The result
 * is deterministic for a given seed and every rung is finite; when no rung is valid the calibrator fails with
 * a message naming the seed and the stage where the last rung stopped, instead of accepting a truncated
 * workload.
 */
public final class FullCycleCalibrator {

    private final DeterministicWorkloadGenerator generator;
    private final FullCycleValidity validity;
    private final List<PropagationConfig> ladder;

    /** Creates a calibrator over {@link DeterministicWorkloadGenerator#FULL_CYCLE_PROPAGATION_LADDER}. */
    public FullCycleCalibrator(DeterministicWorkloadGenerator generator, FullCycleValidity validity) {
        this(generator, validity, DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION_LADDER);
    }

    /** Creates a calibrator over an explicit ordered, non-empty ladder of finite bounds. */
    public FullCycleCalibrator(
            DeterministicWorkloadGenerator generator,
            FullCycleValidity validity,
            List<PropagationConfig> ladder) {
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
        this.validity = Objects.requireNonNull(validity, "validity must not be null");
        this.ladder = List.copyOf(Objects.requireNonNull(ladder, "ladder must not be null"));
        if (this.ladder.isEmpty()) {
            throw new IllegalArgumentException("ladder must not be empty");
        }
    }

    /**
     * Returns the first valid rung of the propagation ladder.
     *
     * <p>Only a propagation truncation ({@code STAGE_LIMIT_REACHED}) moves on to a larger rung, because a
     * larger bound can fix nothing else and only adds work. An exhausted cycle budget or any other validity
     * failure stops at once with its own message, so the cause is not attributed to the propagation bound.
     *
     * @throws IllegalStateException when a rung fails for a non-propagation reason or every rung truncates
     */
    public PropagationConfig calibrate(
            Supplier<GraphTopology> perception,
            Supplier<GraphTopology> reasoning,
            AdaptationPolicy policy,
            ResonanceMemoryPort memoryPort,
            ActionCapability actionCapability,
            List<Signal> initialSignals,
            CognitiveBudget budget) {
        Objects.requireNonNull(perception, "perception must not be null");
        Objects.requireNonNull(reasoning, "reasoning must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        var inputs = List.copyOf(Objects.requireNonNull(initialSignals, "initialSignals must not be null"));

        String lastTruncation = "no rung evaluated";
        for (var rung : ladder) {
            var setup = generator.generateFullCycleSetup(
                    perception.get(), reasoning.get(), policy, memoryPort, actionCapability, rung);
            var result = setup.cycle().execute(setup.monad(), inputs, budget);
            try {
                validity.require(result);
                return rung;
            } catch (IllegalStateException failure) {
                var snapshot = result.snapshot();
                var reached = result.stageResults().stream().map(CognitiveStageResult::kind).toList();
                if (result.termination() == CognitiveCycleTermination.STAGE_LIMIT_REACHED
                        && !snapshot.stepBudgetExhausted()
                        && !snapshot.signalBudgetExhausted()) {
                    lastTruncation = "maxHops=" + rung.maxHops() + " ended " + result.termination()
                            + " after stages " + reached;
                    continue;
                }
                if (snapshot.stepBudgetExhausted() || snapshot.signalBudgetExhausted()
                        || snapshot.traceBudgetExhausted()
                        || result.termination() == CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED) {
                    throw new IllegalStateException("seed " + generator.seed()
                            + ": the cycle budget is exhausted at maxHops=" + rung.maxHops()
                            + " (steps " + snapshot.processedSteps() + "/" + budget.maxSteps()
                            + ", signals " + snapshot.acceptedSignals() + "/" + budget.maxSignals()
                            + "); a larger propagation bound cannot fix this, raise the CognitiveBudget", failure);
                }
                throw new IllegalStateException("seed " + generator.seed() + ": the full-cycle workload is invalid at maxHops="
                        + rung.maxHops() + " for a reason unrelated to the propagation bound ("
                        + result.termination() + " after stages " + reached + "): " + failure.getMessage(), failure);
            }
        }
        var largest = ladder.getLast();
        throw new IllegalStateException("seed " + generator.seed()
                + " is still truncated at the largest propagation bound, maxHops=" + largest.maxHops()
                + ", maxSteps=" + largest.maxSteps() + "; last attempt: " + lastTruncation);
    }
}
