package monada.neuron.evaluation.wave;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

/**
 * Tie-aware rank AUC and a seeded, paired cluster bootstrap over primitive arrays.
 *
 * <p>The AUC is the Mann-Whitney statistic {@code P(positive > negative) + 0.5 · P(tie)}, computed by sorting the
 * positive and the negative scores separately and merging them: O(n log n) time, two primitive arrays. A resample
 * draws clusters with replacement from {@code java.util.Random(seed)}; every arm is scored on the same resample, so
 * the difference of two arms has its own interval (paired). Interval bounds are the order statistics
 * {@code floor(0.025·B) + 1} and {@code ceil(0.975·B)} of the {@code B} sorted resample values.
 */
final class RankStatistics {

    /**
     * Returns the AUC of {@code scores} against {@code positive} over the pairs listed in {@code sample} (repetitions
     * allowed).
     *
     * @throws IllegalArgumentException if a score is NaN or the sample lacks a positive or a negative pair
     */
    double auc(double[] scores, boolean[] positive, int[] sample) {
        int positives = 0;
        for (int pair : sample) {
            if (positive[pair]) {
                positives++;
            }
        }
        int negatives = sample.length - positives;
        if (positives == 0 || negatives == 0) {
            throw new IllegalArgumentException("an AUC needs positive and negative pairs, got " + positives
                    + " positive and " + negatives + " negative");
        }
        var positiveScores = new double[positives];
        var negativeScores = new double[negatives];
        int p = 0;
        int n = 0;
        for (int pair : sample) {
            double score = scores[pair];
            if (Double.isNaN(score)) {
                throw new IllegalArgumentException("score of pair " + pair + " is NaN");
            }
            // + 0.0 maps -0.0 to 0.0, so the sort order agrees with numeric equality.
            if (positive[pair]) {
                positiveScores[p++] = score + 0.0;
            } else {
                negativeScores[n++] = score + 0.0;
            }
        }
        Arrays.sort(positiveScores);
        Arrays.sort(negativeScores);
        double wins = 0.0;
        int below = 0;
        int equal = 0;
        for (double score : positiveScores) {
            while (below < negatives && negativeScores[below] < score) {
                below++;
            }
            if (equal < below) {
                equal = below;
            }
            while (equal < negatives && negativeScores[equal] == score) {
                equal++;
            }
            wins += below + 0.5 * (equal - below);
        }
        return wins / ((double) positives * negatives);
    }

    /** Returns the AUC over every pair. */
    double auc(double[] scores, boolean[] positive) {
        return auc(scores, positive, identity(scores.length));
    }

    /**
     * Returns {@code B} bootstrap AUCs per arm: {@code result[arm][b]}. Resample {@code b} draws {@code clusters.length}
     * clusters with replacement and pools their pairs.
     *
     * @param armScores per arm, the score of every pair
     * @param positive per pair, whether it is a positive
     * @param clusters per cluster, the pairs it contributes
     * @param resamples number of resamples {@code B}
     * @param seed resampling seed
     */
    double[][] bootstrap(double[][] armScores, boolean[] positive, int[][] clusters, int resamples, long seed) {
        Objects.requireNonNull(armScores, "armScores must not be null");
        if (resamples < 1 || clusters.length == 0) {
            throw new IllegalArgumentException("resamples and clusters must be positive");
        }
        int pairsPerResample = 0;
        for (int[] cluster : clusters) {
            pairsPerResample += cluster.length;
        }
        var random = new Random(seed);
        var result = new double[armScores.length][resamples];
        var drawn = new int[clusters.length];
        var sample = new int[pairsPerResample];
        for (int b = 0; b < resamples; b++) {
            int size = 0;
            for (int c = 0; c < clusters.length; c++) {
                drawn[c] = random.nextInt(clusters.length);
                size += clusters[drawn[c]].length;
            }
            int[] pairs = size == sample.length ? sample : new int[size];
            int at = 0;
            for (int cluster : drawn) {
                for (int pair : clusters[cluster]) {
                    pairs[at++] = pair;
                }
            }
            for (int arm = 0; arm < armScores.length; arm++) {
                result[arm][b] = auc(armScores[arm], positive, pairs);
            }
        }
        return result;
    }

    /** Returns the {@code [lower, upper]} 95% percentile interval of resample values (the input is not changed). */
    Interval interval(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int lower = (int) StrictMath.floor(0.025 * sorted.length);
        int upper = (int) StrictMath.ceil(0.975 * sorted.length) - 1;
        return new Interval(sorted[lower], sorted[upper]);
    }

    /** Returns {@code first[b] - second[b]} per resample. */
    double[] difference(double[] first, double[] second) {
        var difference = new double[first.length];
        for (int b = 0; b < first.length; b++) {
            difference[b] = first[b] - second[b];
        }
        return difference;
    }

    private int[] identity(int length) {
        var indices = new int[length];
        for (int i = 0; i < length; i++) {
            indices[i] = i;
        }
        return indices;
    }

    /**
     * A closed interval.
     *
     * @param lower lower bound
     * @param upper upper bound
     */
    record Interval(double lower, double upper) {
    }
}
