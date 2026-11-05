package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseMetrics.Labels;
import monada.neuron.evaluation.bilingual.SenseMetrics.Outputs;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SenseMetricsTest {

    private final SenseMetrics metrics = new SenseMetrics();

    @Test
    void handComputedFixtureIsReproduced() {
        Map<String, double[]> values = new MetricsFixture().comparisons();
        assertEquals(21, values.size());
        values.forEach((name, pair) -> {
            if (Double.isNaN(pair[1])) {
                assertTrue(Double.isNaN(pair[0]), name + " must be n/a");
            } else {
                assertEquals(pair[1], pair[0], 1e-12, name);
            }
        });
        assertTrue(new MetricsFixture().check().passed());
    }

    @Test
    void aurcAndAurocDoNotDependOnTheOrderOfTiedItems() {
        var labels = new Labels(new int[4], new byte[] {0, 0, SenseMetrics.UNA, 0}, new int[] {1, 1, 0, 1});
        var outputs = new Outputs(new int[] {0, 1, 0, 0}, new double[] {0.5, 0.5, 0.5, 0.9},
                new boolean[] {true, true, true, true});
        int[] forward = {0, 1, 2, 3};
        int[] backward = {3, 2, 1, 0};
        assertEquals(metrics.aurc(labels, outputs, forward), metrics.aurc(labels, outputs, backward));
        assertEquals(metrics.auroc(labels, outputs, forward), metrics.auroc(labels, outputs, backward));
        // Blocks: {3} correct (risk 0), then {0, 1, 2} with 2 errors (risk 2/4): AURC = 1/4 x 0 + 3/4 x 1/2.
        assertEquals(0.375, metrics.aurc(labels, outputs, forward).getAsDouble(), 1e-15);
        // The UNA item ties with two ANS items and is below the third: (1 + 1/2 + 1/2) / 3.
        assertEquals(2.0 / 3, metrics.auroc(labels, outputs, forward).getAsDouble(), 1e-15);
    }

    @Test
    void bootstrapResamplesRepeatItemsOfADrawnFamily() {
        var bootstrap = new ClusterBootstrap(List.of("f1", "f1", "f2"), 50, ClusterBootstrap.SEED);
        double[] sizes = bootstrap.values(items -> items.length);
        for (double size : sizes) {
            assertTrue(size >= 2 && size <= 4, "two families drawn: 1 or 2 items each");
        }
        var again = new ClusterBootstrap(List.of("f1", "f1", "f2"), 50, ClusterBootstrap.SEED);
        assertEquals(Arrays.toString(sizes), Arrays.toString(again.values(items -> items.length)));
        ClusterBootstrap.Interval interval = bootstrap.percentile95(new double[] {Double.NaN, 1, 2, 3});
        assertEquals(1, interval.undefined());
        assertEquals(1.0, interval.lower());
        assertEquals(3.0, interval.upper());
    }
}
