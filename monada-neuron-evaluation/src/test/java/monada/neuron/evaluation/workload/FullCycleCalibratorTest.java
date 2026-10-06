package monada.neuron.evaluation.workload;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.runtime.graph.PropagationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullCycleCalibratorTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(5_000, 5_000, 10_000);

    private PropagationConfig calibrate(long seed, CognitiveBudget budget) {
        var generator = new DeterministicWorkloadGenerator(seed);
        return new FullCycleCalibrator(generator, new FullCycleValidity()).calibrate(
                () -> generator.generateGraph(50, 3),
                () -> generator.generateGraph(50, 3),
                new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT),
                new DeterministicMemoryFixture(),
                new DeterministicActionFixture(),
                generator.generateSignals(5),
                budget);
    }

    @Test
    void defaultSeedKeepsTheFirstRung() {
        assertEquals(DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION, calibrate(42L, BUDGET));
    }

    @Test
    void seedThatTruncatesTheFirstRungIsCalibratedToALargerFiniteBound() {
        var chosen = calibrate(10_365L, BUDGET);

        assertNotEquals(DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION, chosen);
        assertTrue(chosen.maxHops() > 8 && chosen.maxHops() <= 64);
        assertEquals(2_000, chosen.maxSteps());
    }

    @Test
    void everySeedInASweepCalibratesWithinTheLadder() {
        for (long seed = 0; seed < 100; seed++) {
            final long current = seed;
            assertDoesNotThrow(() -> calibrate(current, BUDGET), "seed " + current);
        }
    }

    @Test
    void failsExplicitlyWithTheSeedWhenNoRungIsValid() {
        var failure = assertThrows(IllegalStateException.class, () -> calibrate(42L, new CognitiveBudget(3, 3, 10)));

        assertTrue(failure.getMessage().contains("seed 42"), failure.getMessage());
        assertTrue(failure.getMessage().contains("maxHops=64"), failure.getMessage());
    }
}
