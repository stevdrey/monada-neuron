package monada.neuron.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypothesisRankingTest {

    @Test
    void higherScoreRanksFirstRegardlessOfSequence() {
        assertAll(
                () -> assertTrue(HypothesisRanking.compare(0.9, 5, 0.1, 0) < 0),
                () -> assertTrue(HypothesisRanking.compare(0.1, 0, 0.9, 5) > 0));
    }

    @Test
    void equalScoresRankLowerSequenceFirst() {
        assertAll(
                () -> assertTrue(HypothesisRanking.compare(0.5, 1, 0.5, 3) < 0),
                () -> assertTrue(HypothesisRanking.compare(0.5, 3, 0.5, 1) > 0),
                () -> assertEquals(0, HypothesisRanking.compare(0.5, 3, 0.5, 3)));
    }

    @Test
    void positiveZeroRanksBeforeNegativeZero() {
        assertAll(
                () -> assertTrue(HypothesisRanking.compare(0.0, 9, -0.0, 0) < 0),
                () -> assertTrue(HypothesisRanking.compare(-0.0, 0, 0.0, 9) > 0));
    }

    @Test
    void infinitiesRankNormally() {
        assertAll(
                () -> assertTrue(HypothesisRanking.compare(Double.POSITIVE_INFINITY, 1, 1.0, 0) < 0),
                () -> assertTrue(HypothesisRanking.compare(Double.NEGATIVE_INFINITY, 0, 0.0, 1) > 0));
    }

    @Test
    void requireNoNaNRejectsNaNAtAnyPositionAndAcceptsInfinities() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> HypothesisRanking.requireNoNaN(new double[] {Double.NaN})),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> HypothesisRanking.requireNoNaN(new double[] {0.1, 0.2, Double.NaN})),
                () -> assertDoesNotThrow(() -> HypothesisRanking.requireNoNaN(
                        new double[] {Double.NEGATIVE_INFINITY, 0.0, Double.POSITIVE_INFINITY})),
                () -> assertDoesNotThrow(() -> HypothesisRanking.requireNoNaN(new double[0])));
    }
}
