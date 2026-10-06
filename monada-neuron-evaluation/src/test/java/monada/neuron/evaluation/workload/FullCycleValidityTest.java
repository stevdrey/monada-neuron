package monada.neuron.evaluation.workload;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.runtime.graph.PropagationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullCycleValidityTest {

    private static final CognitiveBudget BUDGET = DeterministicWorkloadGenerator.FULL_CYCLE_BUDGET;

    private final FullCycleValidity validity = new FullCycleValidity();

    private CognitiveCycleResult run(PropagationConfig propagation) {
        var generator = new DeterministicWorkloadGenerator(42L);
        var setup = generator.generateFullCycleSetup(
                generator.generateGraph(50, 3),
                generator.generateGraph(50, 3),
                new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT),
                new DeterministicMemoryFixture(),
                new DeterministicActionFixture(),
                propagation);
        return setup.cycle().execute(setup.monad(), new DeterministicWorkloadGenerator(42L).generateSignals(5), BUDGET);
    }

    @Test
    void legacyRouteAllBoundTruncatesPerceptionBeforeMemoryRecall() {
        var result = run(DeterministicWorkloadGenerator.LEGACY_TRUNCATING_PROPAGATION);

        var kinds = result.stageResults().stream().map(CognitiveStageResult::kind).toList();
        assertEquals(CognitiveCycleTermination.STAGE_LIMIT_REACHED, result.termination());
        assertEquals(List.of(CognitiveStageKind.PERCEPTION), kinds);
        assertFalse(kinds.contains(CognitiveStageKind.MEMORY_RECALL));
        assertThrows(IllegalStateException.class, () -> validity.require(result));
    }

    @Test
    void correctedConfigurationExecutesAllFiveStagesInOrder() {
        var result = run(DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION);

        assertEquals(DeterministicWorkloadGenerator.FULL_CYCLE_ORDER,
                result.stageResults().stream().map(CognitiveStageResult::kind).toList());
        assertEquals(CognitiveCycleTermination.COMPLETED, result.termination());
        assertFalse(result.snapshot().stepBudgetExhausted());
        assertFalse(result.snapshot().signalBudgetExhausted());
        assertTrue(result.snapshot().processedSteps() > 0);
        assertDoesNotThrow(() -> validity.require(result));
    }

    @Test
    void defaultSetupOverloadUsesTheCorrectedBound() {
        var generator = new DeterministicWorkloadGenerator(42L);
        var setup = generator.generateFullCycleSetup(
                generator.generateGraph(50, 3),
                generator.generateGraph(50, 3),
                new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT),
                new DeterministicMemoryFixture(),
                new DeterministicActionFixture());
        var result = setup.cycle().execute(setup.monad(), generator.generateSignals(5), BUDGET);

        assertDoesNotThrow(() -> validity.require(result));
    }

    @Test
    void repeatedRunsWithTheSameSeedProduceTheSameObservableResult() {
        var first = run(DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION);
        var second = run(DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION);

        assertEquals(first, second);
    }

    @Test
    void validityRejectsAnExhaustedBudget() {
        var generator = new DeterministicWorkloadGenerator(42L);
        var setup = generator.generateFullCycleSetup(
                generator.generateGraph(50, 3),
                generator.generateGraph(50, 3),
                new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT),
                new DeterministicMemoryFixture(),
                new DeterministicActionFixture());
        var result = setup.cycle().execute(setup.monad(), generator.generateSignals(5), new CognitiveBudget(3, 3, 10));

        assertThrows(IllegalStateException.class, () -> validity.require(result));
    }
}
