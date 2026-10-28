package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.wave.SelectiveMetrics.Reliability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reliability bins, ECE and the risk-coverage curve on hand cases. */
class SelectiveMetricsTest {

    private final SelectiveMetrics metrics = new SelectiveMetrics();

    @Test
    void reliabilityBinsAndExpectedCalibrationError() {
        double[] scores = {0.95, 0.95, 0.55, 0.55, 1.0, 0.3};
        boolean[] correct = {true, false, true, true, true, false};
        boolean[] included = {true, true, true, true, true, false};
        Reliability reliability = metrics.reliability(scores, correct, included);

        assertEquals(3, reliability.counts()[9], "1.0 falls in the last, closed bin");
        assertEquals(2, reliability.counts()[5]);
        assertEquals(0, reliability.counts()[3], "excluded items are not binned");
        // Bin 9: confidence 0.9667, accuracy 0.6667; bin 5: confidence 0.55, accuracy 1.
        assertEquals(3.0 / 5 * (2.9 / 3 - 2.0 / 3) + 2.0 / 5 * 0.45, reliability.ece(), 1e-12);
    }

    @Test
    void riskCoverageAnswersTheHighestScoresFirst() {
        double[] scores = {0.9, 0.1, 0.5, 0.5};
        boolean[] correct = {true, false, false, true};
        boolean[] included = {true, true, true, true};
        double[] curve = metrics.riskCoverage(scores, correct, included);

        assertEquals(SelectiveMetrics.BINS, curve.length);
        assertEquals(1.0, curve[0]);
        assertEquals(0.5, curve[4], "ties go to the lower index: items 0 and 2");
        assertArrayEquals(new double[] {2.0 / 3, 0.5}, new double[] {curve[6], curve[9]}, 1e-12);
    }
}
