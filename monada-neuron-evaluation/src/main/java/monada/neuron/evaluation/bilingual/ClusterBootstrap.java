package monada.neuron.evaluation.bilingual;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * The paired cluster bootstrap over held-out families of {@code bilingual-sense/1}: {@code B} resamples of the families
 * with replacement, drawn once from {@link SplittableRandom} with the registered seed, so every arm and every
 * difference is evaluated on the same families. A resample is the multiset of the items of the drawn families.
 *
 * <p>A statistic that is undefined on a resample (returned as {@code NaN}, for example a sense absent from it) is left
 * out of the interval and counted.
 */
final class ClusterBootstrap {

    /** The registered bootstrap seed. */
    static final long SEED = 0x1840_B007L;
    /** The registered number of resamples. */
    static final int RESAMPLES = 2000;

    /**
     * A percentile interval.
     *
     * @param lower lower bound
     * @param upper upper bound
     * @param undefined resamples on which the statistic was undefined
     */
    record Interval(double lower, double upper, int undefined) {
    }

    private final int[][] samples;

    /**
     * Draws the resamples.
     *
     * @param family the family key of each item
     */
    ClusterBootstrap(List<String> family, int resamples, long seed) {
        var members = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < family.size(); i++) {
            members.computeIfAbsent(family.get(i), _ -> new ArrayList<>()).add(i);
        }
        int[][] families = members.values().stream()
                .map(list -> list.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
        var random = new SplittableRandom(seed);
        samples = new int[resamples][];
        for (int r = 0; r < resamples; r++) {
            var items = new ArrayList<Integer>();
            for (int f = 0; f < families.length; f++) {
                for (int item : families[random.nextInt(families.length)]) {
                    items.add(item);
                }
            }
            samples[r] = items.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** Returns the statistic on every resample. */
    double[] values(ToDoubleFunction<int[]> statistic) {
        var values = new double[samples.length];
        for (int r = 0; r < samples.length; r++) {
            values[r] = statistic.applyAsDouble(samples[r]);
        }
        return values;
    }

    /** Returns a vector statistic on every resample, indexed by resample. */
    double[][] table(Function<int[], double[]> statistic) {
        var table = new double[samples.length][];
        for (int r = 0; r < samples.length; r++) {
            table[r] = statistic.apply(samples[r]);
        }
        return table;
    }

    /**
     * Returns the 95% percentile interval, with the convention of {@code wave.RankStatistics}: sorted values at
     * {@code floor(0.025 B')} and {@code ceil(0.975 B') - 1}, {@code B'} the defined resamples.
     */
    Interval percentile95(double[] values) {
        double[] sorted = defined(values);
        if (sorted.length == 0) {
            return new Interval(Double.NaN, Double.NaN, values.length);
        }
        int lower = (int) StrictMath.floor(0.025 * sorted.length);
        int upper = (int) StrictMath.ceil(0.975 * sorted.length) - 1;
        return new Interval(sorted[lower], sorted[upper], values.length - sorted.length);
    }

    /** Returns the one-sided lower bound at {@code level}: the sorted value at {@code floor((1 - level) B')}. */
    double lowerBound(double[] values, double level) {
        double[] sorted = defined(values);
        if (sorted.length == 0) {
            return Double.NaN;
        }
        return sorted[(int) StrictMath.floor((1.0 - level) * sorted.length)];
    }

    private double[] defined(double[] values) {
        double[] sorted = Arrays.stream(values).filter(v -> !Double.isNaN(v)).toArray();
        Arrays.sort(sorted);
        return sorted;
    }
}
