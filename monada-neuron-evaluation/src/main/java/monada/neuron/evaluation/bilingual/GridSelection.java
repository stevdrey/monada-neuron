package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.SenseArm.Prediction;
import monada.neuron.evaluation.bilingual.SenseMetrics.Labels;
import monada.neuron.evaluation.bilingual.SenseMetrics.Outputs;

import java.util.List;

/**
 * The one selection rule of {@code bilingual-sense/1}, on validation items only: among the grid points whose
 * {@code ANS} coverage is at least {@code 0.70}, the one with the least selective risk over all validation items (ties
 * to higher coverage, then to grid order); if no point reaches {@code 0.70}, the point with the highest coverage (ties to
 * grid order), reported as such. The held-out split is never read here.
 */
final class GridSelection {

    /** The registered minimum validation {@code ANS} coverage. */
    static final double MIN_COVERAGE = 0.70;

    /**
     * A selected grid point.
     *
     * @param configuration the arm configuration
     * @param threshold index into the arm's thresholds
     * @param tau the threshold value
     * @param coverage validation {@code ANS} coverage at the point
     * @param risk validation selective risk at the point
     * @param reachedCoverage whether the point reached the 0.70 coverage minimum
     */
    record Selection(int configuration, int threshold, double tau, double coverage, double risk,
                     boolean reachedCoverage) {
    }

    private final SenseMetrics metrics = new SenseMetrics();

    /** Selects the grid point of an arm over validation items with their gold. */
    Selection select(SenseArm arm, List<SenseItem> items, Labels labels) {
        int[] all = metrics.all(items.size());
        double[] thresholds = arm.thresholds();
        Selection best = null;
        Selection widest = null;
        for (int configuration = 0; configuration < arm.configurations(); configuration++) {
            var choice = new int[items.size()];
            var confidence = new double[items.size()];
            for (int i = 0; i < items.size(); i++) {
                Prediction prediction = arm.predict(items.get(i), configuration);
                choice[i] = prediction.choice();
                confidence[i] = prediction.confidence();
            }
            for (int t = 0; t < thresholds.length; t++) {
                var answered = new boolean[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    answered[i] = new Prediction(choice[i], confidence[i]).answers(thresholds[t]);
                }
                var outputs = new Outputs(choice, confidence, answered);
                double coverage = metrics.coverage(labels, outputs, all).orElse(0.0);
                double risk = metrics.selectionRisk(labels, outputs, all);
                boolean reached = coverage >= MIN_COVERAGE;
                var point = new Selection(configuration, t, thresholds[t], coverage, risk, reached);
                if (reached && (best == null || risk < best.risk()
                        || (risk == best.risk() && coverage > best.coverage()))) {
                    best = point;
                }
                if (widest == null || coverage > widest.coverage()) {
                    widest = point;
                }
            }
        }
        return best != null ? best : widest;
    }
}
