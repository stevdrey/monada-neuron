package monada.neuron.evaluation.metrics;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable statistical distribution of latency measurements in nanoseconds.
 */
public record LatencyDistribution(
        int sampleCount,
        double minNanos,
        double medianNanos,
        double p90Nanos,
        double p95Nanos,
        double p99Nanos,
        double maxNanos,
        double meanNanos,
        double stdDevNanos,
        double throughputOpsPerSec) {

    /**
     * Calculates latency statistics from an array of measured elapsed nanoseconds for single-op iterations.
     *
     * @param samples measured nanoseconds per iteration
     * @return latency distribution
     */
    public static LatencyDistribution fromSamples(long[] samples) {
        return fromSamples(samples, 1);
    }

    /**
     * Calculates latency statistics and domain throughput from measured samples and operations per iteration.
     *
     * @param samples measured nanoseconds per iteration
     * @param operationsPerIteration number of domain operations executed within each measured iteration
     * @return latency distribution
     */
    public static LatencyDistribution fromSamples(long[] samples, int operationsPerIteration) {
        Objects.requireNonNull(samples, "samples must not be null");
        if (samples.length == 0) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        if (operationsPerIteration <= 0) {
            throw new IllegalArgumentException("operationsPerIteration must be positive, got: " + operationsPerIteration);
        }

        var sorted = samples.clone();
        Arrays.sort(sorted);

        int count = sorted.length;
        double min = sorted[0];
        double max = sorted[count - 1];

        double sum = 0.0;
        for (long sample : sorted) {
            sum += sample;
        }
        double mean = sum / count;

        double sumSquaredDiff = 0.0;
        for (long sample : sorted) {
            double diff = sample - mean;
            sumSquaredDiff += diff * diff;
        }
        double stdDev = Math.sqrt(sumSquaredDiff / count);

        double median = percentile(sorted, 50.0);
        double p90 = percentile(sorted, 90.0);
        double p95 = percentile(sorted, 95.0);
        double p99 = percentile(sorted, 99.0);

        double throughput = mean > 0.0 ? ((double) operationsPerIteration * 1_000_000_000.0 / mean) : 0.0;

        return new LatencyDistribution(count, min, median, p90, p95, p99, max, mean, stdDev, throughput);
    }


    private static double percentile(long[] sorted, double p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = (p / 100.0) * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted[lower];
        }
        double weight = rank - lower;
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight;
    }
}
