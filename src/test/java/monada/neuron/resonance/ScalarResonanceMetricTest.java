package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalarResonanceMetricTest {

    private static final double TOLERANCE = 1.0e-12;

    private final ResonanceMetric metric = new ScalarResonanceMetric();

    @Test
    void identicalNonSilentStatesHaveFullResonance() {
        var state = new FrequencyState(2.0, 440.0, 0.75);

        assertEquals(1.0, metric.score(state, state), TOLERANCE);
    }

    @Test
    void silentStatesDoNotResonate() {
        var active = new FrequencyState(1.0, 440.0, 0.0);
        var silentWithFrequency = new FrequencyState(0.0, 440.0, 0.0);

        assertAll(
                () -> assertEquals(0.0,
                        metric.score(FrequencyState.ZERO, FrequencyState.ZERO),
                        TOLERANCE),
                () -> assertEquals(0.0, metric.score(silentWithFrequency, active), TOLERANCE),
                () -> assertEquals(0.0, metric.score(active, silentWithFrequency), TOLERANCE));
    }

    @Test
    void amplitudeSimilarityUsesRelativeMagnitude() {
        var first = new FrequencyState(2.0, 100.0, 0.0);
        var second = new FrequencyState(1.0, 100.0, 0.0);

        assertEquals(0.5, metric.score(first, second), TOLERANCE);
    }

    @Test
    void frequencySimilarityUsesRelativeDistance() {
        var reference = new FrequencyState(1.0, 100.0, 0.0);
        var near = new FrequencyState(1.0, 80.0, 0.0);
        var distant = new FrequencyState(1.0, 20.0, 0.0);

        double nearScore = metric.score(reference, near);
        double distantScore = metric.score(reference, distant);

        assertAll(
                () -> assertEquals(0.8, nearScore, TOLERANCE),
                () -> assertEquals(0.2, distantScore, TOLERANCE),
                () -> assertTrue(nearScore > distantScore));
    }

    @Test
    void zeroFrequenciesMatchOnlyEachOther() {
        var firstZero = new FrequencyState(1.0, 0.0, 0.0);
        var secondZero = new FrequencyState(1.0, 0.0, 0.0);
        var positive = new FrequencyState(1.0, 100.0, 0.0);

        assertAll(
                () -> assertEquals(1.0, metric.score(firstZero, secondZero), TOLERANCE),
                () -> assertEquals(0.0, metric.score(firstZero, positive), TOLERANCE),
                () -> assertEquals(0.0, metric.score(positive, firstZero), TOLERANCE));
    }

    @Test
    void phaseSimilarityWrapsAndTracksAlignment() {
        var reference = new FrequencyState(1.0, 100.0, 0.0);
        var fullCycle = new FrequencyState(1.0, 100.0, 2.0 * StrictMath.PI);
        var quarterCycle = new FrequencyState(1.0, 100.0, StrictMath.PI / 2.0);
        var opposite = new FrequencyState(1.0, 100.0, StrictMath.PI);

        assertAll(
                () -> assertEquals(1.0, metric.score(reference, fullCycle), TOLERANCE),
                () -> assertEquals(0.5, metric.score(reference, quarterCycle), TOLERANCE),
                () -> assertEquals(0.0, metric.score(reference, opposite), TOLERANCE));
    }

    @Test
    void combinedFactorsHaveStableRegressionScore() {
        var first = new FrequencyState(2.0, 200.0, 0.0);
        var second = new FrequencyState(1.0, 100.0, StrictMath.PI / 2.0);

        assertEquals(0.125, metric.score(first, second), TOLERANCE);
    }

    @Test
    void deterministicSamplesAreSymmetricFiniteAndBounded() {
        var samples = List.of(
                FrequencyState.ZERO,
                new FrequencyState(1.0, 0.0, 0.0),
                new FrequencyState(1.0, 100.0, 0.0),
                new FrequencyState(2.0, 200.0, StrictMath.PI / 2.0),
                new FrequencyState(Double.MIN_VALUE, Double.MAX_VALUE, Double.MAX_VALUE),
                new FrequencyState(Double.MAX_VALUE, Double.MIN_VALUE, -Double.MAX_VALUE));

        for (var first : samples) {
            for (var second : samples) {
                double forward = metric.score(first, second);
                double reverse = metric.score(second, first);

                assertAll(
                        () -> assertEquals(forward, reverse, TOLERANCE),
                        () -> assertTrue(Double.isFinite(forward)),
                        () -> assertTrue(forward >= 0.0),
                        () -> assertTrue(forward <= 1.0));
            }
        }
    }

    @Test
    void extremeFinitePhasesAreHandledWithoutOverflow() {
        var first = new FrequencyState(1.0, 1.0, Double.MAX_VALUE);
        var second = new FrequencyState(1.0, 1.0, -Double.MAX_VALUE);

        double score = metric.score(first, second);

        assertAll(
                () -> assertTrue(Double.isFinite(score)),
                () -> assertTrue(score >= 0.0),
                () -> assertTrue(score <= 1.0),
                () -> assertEquals(score, metric.score(second, first), TOLERANCE));
    }

    @Test
    void nullStatesAreRejected() {
        var state = new FrequencyState(1.0, 1.0, 0.0);

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> metric.score(null, state)),
                () -> assertThrows(NullPointerException.class, () -> metric.score(state, null)));
    }

    @Test
    void invalidNumericComponentsAreRejectedBeforeScoring() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new FrequencyState(Double.NaN, 1.0, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new FrequencyState(1.0, Double.POSITIVE_INFINITY, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new FrequencyState(1.0, 1.0, Double.NEGATIVE_INFINITY)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new FrequencyState(-1.0, 1.0, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new FrequencyState(1.0, -1.0, 0.0)));
    }
}
