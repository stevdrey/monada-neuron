package monada.neuron.evaluation.workload;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionRequest;
import monada.neuron.action.ActionResult;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.Signal;
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
                new DeterministicActionFixture(),
                DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION);
        var result = setup.cycle().execute(setup.monad(), generator.generateSignals(5), new CognitiveBudget(3, 3, 10));

        assertThrows(IllegalStateException.class, () -> validity.require(result));
    }

    @Test
    void validityRejectsAMemoryPortWhoseBehaviorDiffersFromTheReference() {
        var result = runWith(new DeterministicMemoryFixture(0.5), new DeterministicActionFixture());

        var failure = assertThrows(IllegalStateException.class, () -> validity.require(result));
        assertTrue(failure.getMessage().contains("memory"), failure.getMessage());
    }

    @Test
    void validityRejectsAnActionCapabilityWhoseBehaviorDiffersFromTheReference() {
        var shifted = new ActionCapability() {
            private final DeterministicActionFixture delegate = new DeterministicActionFixture();

            @Override
            public ActionResult execute(ActionRequest request) {
                var base = delegate.execute(request);
                var observations = base.observations().stream()
                        .map(o -> new Signal(o.kind(), new FrequencyState(
                                o.frequencyState().amplitude(), o.frequencyState().frequency() + 1.0, 0.0)))
                        .toList();
                return new ActionResult(base.status(), base.observationLimit(), observations);
            }
        };
        var result = runWith(new DeterministicMemoryFixture(), shifted);

        var failure = assertThrows(IllegalStateException.class, () -> validity.require(result));
        assertTrue(failure.getMessage().contains("action"), failure.getMessage());
    }

    private CognitiveCycleResult runWith(ResonanceMemoryPort memory, ActionCapability action) {
        var generator = new DeterministicWorkloadGenerator(42L);
        var setup = generator.generateFullCycleSetup(
                generator.generateGraph(50, 3),
                generator.generateGraph(50, 3),
                new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT),
                memory,
                action,
                DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION);
        return setup.cycle().execute(setup.monad(), generator.generateSignals(5), BUDGET);
    }
}
