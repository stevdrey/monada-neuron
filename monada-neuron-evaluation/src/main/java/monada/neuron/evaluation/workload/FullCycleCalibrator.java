package monada.neuron.evaluation.workload;

import monada.neuron.action.ActionCapability;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.GraphTopology;
import monada.neuron.evolution.AdaptationPolicy;
import monada.neuron.memory.ResonanceMemoryPort;
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

    /** Creates a calibrator for the topologies and fixtures produced by {@code generator}. */
    public FullCycleCalibrator(DeterministicWorkloadGenerator generator, FullCycleValidity validity) {
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
        this.validity = Objects.requireNonNull(validity, "validity must not be null");
    }

    /**
     * Returns the first valid rung of the propagation ladder.
     *
     * @throws IllegalStateException when no rung executes all five stages
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

        String lastFailure = "no rung evaluated";
        for (var rung : DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION_LADDER) {
            var setup = generator.generateFullCycleSetup(
                    perception.get(), reasoning.get(), policy, memoryPort, actionCapability, rung);
            var result = setup.cycle().execute(setup.monad(), inputs, budget);
            try {
                validity.require(result);
                return rung;
            } catch (IllegalStateException failure) {
                var reached = result.stageResults().stream().map(CognitiveStageResult::kind).toList();
                lastFailure = "maxHops=" + rung.maxHops() + " ended " + result.termination()
                        + " after stages " + reached + " (" + failure.getMessage() + ")";
            }
        }
        var largest = DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION_LADDER.getLast();
        throw new IllegalStateException("seed " + generator.seed()
                + " has no valid full-cycle propagation bound up to maxHops=" + largest.maxHops()
                + ", maxSteps=" + largest.maxSteps() + "; last attempt: " + lastFailure);
    }
}
