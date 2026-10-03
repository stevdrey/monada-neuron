package monada.neuron.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HypothesisSelectorTest {

    static Stream<HypothesisSelector> selectors() {
        return Stream.of(new BoundedHeapSelector(), new FullSortSelector());
    }

    @ParameterizedTest
    @MethodSource("selectors")
    void ranksByScoreDescendingThenIndexAscending(HypothesisSelector selector) {
        var scores = new double[] {0.5, 0.9, 0.5, 0.9, 0.1};
        assertArrayEquals(new int[] {1, 3, 0, 2, 4}, selector.select(scores, 5));
    }

    @ParameterizedTest
    @MethodSource("selectors")
    void allEqualScoresKeepInsertionOrder(HypothesisSelector selector) {
        var scores = new double[] {0.25, 0.25, 0.25, 0.25, 0.25};
        assertArrayEquals(new int[] {0, 1, 2}, selector.select(scores, 3));
    }

    @ParameterizedTest
    @MethodSource("selectors")
    void handlesBoundaryValuesOfK(HypothesisSelector selector) {
        var scores = new double[] {0.3, 0.7, 0.1};
        assertArrayEquals(new int[0], selector.select(scores, 0));
        assertArrayEquals(new int[] {1}, selector.select(scores, 1));
        assertArrayEquals(new int[] {1, 0}, selector.select(scores, 2));
        assertArrayEquals(new int[] {1, 0, 2}, selector.select(scores, 3));
        assertArrayEquals(new int[] {1, 0, 2}, selector.select(scores, 4));
        assertArrayEquals(new int[] {1, 0, 2}, selector.select(scores, Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @MethodSource("selectors")
    void emptyScoresSelectNothing(HypothesisSelector selector) {
        assertArrayEquals(new int[0], selector.select(new double[0], 3));
    }

    @ParameterizedTest
    @MethodSource("selectors")
    void rejectsNullAndNegativeK(HypothesisSelector selector) {
        assertThrows(NullPointerException.class, () -> selector.select(null, 1));
        assertThrows(IllegalArgumentException.class, () -> selector.select(new double[] {1.0}, -1));
    }

    @ParameterizedTest
    @MethodSource("selectors")
    void rejectsNaNAnywhereInTheScores(HypothesisSelector selector) {
        assertThrows(IllegalArgumentException.class,
                () -> selector.select(new double[] {0.5, Double.NaN, 0.1}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> selector.select(new double[] {Double.NaN}, 0));
    }

    @ParameterizedTest
    @MethodSource("selectors")
    void ranksInfinitiesNormally(HypothesisSelector selector) {
        var scores = new double[] {Double.NEGATIVE_INFINITY, 0.5, Double.POSITIVE_INFINITY};
        assertArrayEquals(new int[] {2, 1, 0}, selector.select(scores, 3));
        assertArrayEquals(new int[] {2}, selector.select(scores, 1));
    }

    @ParameterizedTest
    @MethodSource("selectors")
    void doesNotMutateTheScores(HypothesisSelector selector) {
        var scores = new double[] {0.3, 0.7, 0.1, 0.7};
        var copy = scores.clone();
        selector.select(scores, 2);
        assertArrayEquals(copy, scores);
    }

    @ParameterizedTest
    @MethodSource("selectors")
    void ranksPositiveZeroBeforeNegativeZeroLikeDoubleCompare(HypothesisSelector selector) {
        assertArrayEquals(new int[] {1, 0}, selector.select(new double[] {-0.0, 0.0}, 2));
        assertArrayEquals(new int[] {0, 1}, selector.select(new double[] {0.0, -0.0}, 2));
        assertArrayEquals(new int[] {1}, selector.select(new double[] {-0.0, 0.0}, 1));
        assertArrayEquals(new int[] {0, 2, 1}, selector.select(new double[] {0.0, -0.0, 0.0}, 3));
    }

    @Test
    void boundedHeapEqualsFullSortOnTieHeavyRandomInputs() {
        var heap = new BoundedHeapSelector();
        var sort = new FullSortSelector();
        var random = new Random(2026);
        for (var trial = 0; trial < 300; trial++) {
            var n = random.nextInt(120);
            var scores = new double[n];
            for (var i = 0; i < n; i++) {
                // Few distinct values force many exact ties.
                var bucket = random.nextInt(7);
                scores[i] = bucket == 6 ? -0.0 : bucket / 8.0;
            }
            for (var k : new int[] {0, 1, 2, 5, n / 2, Math.max(0, n - 1), n, n + 3}) {
                assertArrayEquals(sort.select(scores, k), heap.select(scores, k),
                        "n=" + n + " k=" + k + " trial=" + trial);
            }
        }
    }
}
