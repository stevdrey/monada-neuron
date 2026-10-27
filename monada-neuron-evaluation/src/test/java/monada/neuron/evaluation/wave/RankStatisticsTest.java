package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.wave.RankStatistics.Interval;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Rank AUC with midranks and the paired cluster bootstrap. */
class RankStatisticsTest {

    private final RankStatistics statistics = new RankStatistics();

    @Test
    void aucOfHandCases() {
        boolean[] positive = {true, true, false, false};

        assertEquals(1.0, statistics.auc(new double[] {0.9, 0.8, 0.2, 0.1}, positive));
        assertEquals(0.0, statistics.auc(new double[] {0.1, 0.2, 0.8, 0.9}, positive));
        assertEquals(0.5, statistics.auc(new double[] {0.5, 0.5, 0.5, 0.5}, positive));
        // One positive ties one negative, the other wins both: (2 + 1.5) / 4.
        assertEquals(0.875, statistics.auc(new double[] {0.9, 0.4, 0.4, 0.1}, positive));
        assertEquals(1.0, statistics.auc(new double[] {0.0, 0.0, -0.0, -1.0}, positive, new int[] {0, 3}));
        assertEquals(0.5, statistics.auc(new double[] {0.0, 0.0, -0.0, -1.0}, positive, new int[] {0, 2}));
    }

    @Test
    void aucNeedsBothClassesAndRejectsNaN() {
        assertThrows(IllegalArgumentException.class,
                () -> statistics.auc(new double[] {0.1, 0.2}, new boolean[] {true, true}));
        assertThrows(IllegalArgumentException.class,
                () -> statistics.auc(new double[] {Double.NaN, 0.2}, new boolean[] {true, false}));
    }

    @Test
    void bootstrapIsSeededAndPaired() {
        double[][] scores = {{0.9, 0.7, 0.4, 0.3, 0.6, 0.2}, {0.9, 0.7, 0.4, 0.3, 0.6, 0.2}};
        boolean[] positive = {true, true, false, false, true, false};
        int[][] clusters = {{0, 2}, {1, 3}, {4, 5}};

        double[][] first = statistics.bootstrap(scores, positive, clusters, 50, 7L);
        double[][] second = statistics.bootstrap(scores, positive, clusters, 50, 7L);
        assertArrayEquals(first[0], second[0]);
        // Identical arms on the same resamples differ by exactly zero.
        assertArrayEquals(new double[50], statistics.difference(first[0], first[1]));
    }

    @Test
    void intervalUsesThePreRegisteredOrderStatistics() {
        var values = new double[2_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = values.length - i;
        }
        assertEquals(new Interval(51.0, 1_950.0), statistics.interval(values));
    }
}
