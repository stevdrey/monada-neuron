package monada.neuron.evaluation.wave;

import java.util.Arrays;

/**
 * Calibration and selective-prediction metrics of contract {@code ncm-wave-cognition/1} section 14.3 over primitive
 * arrays: reliability bins, expected calibration error and the risk-coverage curve.
 */
final class SelectiveMetrics {

    /** Number of equal-width reliability bins over {@code [0, 1]}. */
    static final int BINS = 10;

    /**
     * Reliability bins of the scored items: per bin {@code [b/10, (b+1)/10)} (the last bin closed), the count, the
     * mean score and the accuracy.
     *
     * @param scores score of each item, in {@code [0, 1]}
     * @param correct whether each item is correct
     * @param included whether each item is scored
     */
    Reliability reliability(double[] scores, boolean[] correct, boolean[] included) {
        var counts = new int[BINS];
        var scoreSums = new double[BINS];
        var correctCounts = new int[BINS];
        int total = 0;
        for (int i = 0; i < scores.length; i++) {
            if (!included[i]) {
                continue;
            }
            int bin = Math.min(BINS - 1, (int) (scores[i] * BINS));
            counts[bin]++;
            scoreSums[bin] += scores[i];
            correctCounts[bin] += correct[i] ? 1 : 0;
            total++;
        }
        var meanScores = new double[BINS];
        var accuracies = new double[BINS];
        double ece = 0.0;
        for (int b = 0; b < BINS; b++) {
            if (counts[b] > 0) {
                meanScores[b] = scoreSums[b] / counts[b];
                accuracies[b] = correctCounts[b] / (double) counts[b];
                ece += counts[b] / (double) total * Math.abs(accuracies[b] - meanScores[b]);
            }
        }
        return new Reliability(counts, meanScores, accuracies, total == 0 ? 0.0 : ece);
    }

    /**
     * Accuracy at coverage {@code 10%, 20%, ..., 100%} when the included items are answered in order of descending
     * score (ties to the lower item index): the risk-coverage curve.
     */
    double[] riskCoverage(double[] scores, boolean[] correct, boolean[] included) {
        int total = 0;
        for (boolean in : included) {
            total += in ? 1 : 0;
        }
        var order = new int[total];
        int at = 0;
        for (int i = 0; i < scores.length; i++) {
            if (included[i]) {
                order[at++] = i;
            }
        }
        // Evaluation-only sort of at most a few thousand indices; the order is total, so the curve is deterministic.
        Integer[] boxed = Arrays.stream(order).boxed().toArray(Integer[]::new);
        Arrays.sort(boxed, (a, b) -> {
            int byScore = Double.compare(scores[b], scores[a]);
            return byScore != 0 ? byScore : Integer.compare(a, b);
        });
        var curve = new double[BINS];
        int answered = 0;
        int right = 0;
        for (int point = 1; point <= BINS; point++) {
            int target = (int) StrictMath.ceil(total * point / (double) BINS);
            while (answered < target) {
                right += correct[boxed[answered++]] ? 1 : 0;
            }
            curve[point - 1] = answered == 0 ? 0.0 : right / (double) answered;
        }
        return curve;
    }

    /**
     * Reliability bins and expected calibration error.
     *
     * @param counts items per bin
     * @param meanScores mean score per bin, {@code 0} for an empty bin
     * @param accuracies accuracy per bin, {@code 0} for an empty bin
     * @param ece expected calibration error {@code Σ (n_b / n) · |acc_b − conf_b|}
     */
    record Reliability(int[] counts, double[] meanScores, double[] accuracies, double ece) {
    }
}
