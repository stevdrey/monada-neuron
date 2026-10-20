package monada.neuron.evaluation.knowledge;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One latency row of a knowledge report: its samples, reported as median and p95 (nearest rank), and how they were
 * taken, as the protocol requires ("Resources (L6)"), with the field names of the integration evaluation. Every
 * knowledge latency row is written through this type, so none is a bare scalar. Rows with different sampling are not
 * comparable, and no row is ever part of a verdict.
 *
 * @param samples measured durations in nanoseconds, one per measurement iteration
 * @param warmupIterations unmeasured executions before the measured ones
 * @param operationsPerIteration operations inside one measured sample
 * @param measurementMethod how a sample was timed
 * @param validationInWindow whether result validation (mapping, hashing) ran inside the timed window
 * @param gcTelemetryScope which GC or allocation telemetry was collected for the row
 * @param excluded why each excluded sample was excluded; a diagnostic, never a semantic check, because latency must not
 *     affect the verdict
 */
record LatencyRow(long[] samples, int warmupIterations, int operationsPerIteration, String measurementMethod,
        boolean validationInWindow, String gcTelemetryScope, List<String> excluded) {

    /** Repetitions of every knowledge latency row that is not already sampled once per query. */
    static final int REPETITIONS = 5;

    /** Measurement method of every knowledge latency row. */
    static final String WALL_CLOCK = "System.nanoTime wall clock around the timed operation; not JMH and not"
            + " EvaluationMetricsCollector";

    /** How every knowledge report's run configuration describes its latency rows. */
    static final String RUN_CONFIGURATION_NOTE = "latency rows report median and p95, and each row records its own"
            + " sampling (measurementIterations, warmupIterations, ...) in its perf.* fields; exploratory wall-clock"
            + " values, never part of a verdict";

    /** GC telemetry scope of every knowledge latency row. */
    static final String NO_GC_TELEMETRY = "none collected";

    LatencyRow {
        samples = Objects.requireNonNull(samples, "samples must not be null").clone();
        Objects.requireNonNull(measurementMethod, "measurementMethod must not be null");
        Objects.requireNonNull(gcTelemetryScope, "gcTelemetryScope must not be null");
        excluded = List.copyOf(excluded);
    }

    /** A row with no excluded sample. */
    LatencyRow(long[] samples, int warmupIterations, int operationsPerIteration, String measurementMethod,
            boolean validationInWindow, String gcTelemetryScope) {
        this(samples, warmupIterations, operationsPerIteration, measurementMethod, validationInWindow, gcTelemetryScope,
                List.of());
    }

    /** Returns this row with {@code reasons} recorded as excluded samples. */
    LatencyRow excluding(List<String> reasons) {
        return new LatencyRow(samples, warmupIterations, operationsPerIteration, measurementMethod, validationInWindow,
                gcTelemetryScope, reasons);
    }

    /** Returns the successful durations collected in {@code values} as a sample array. */
    static long[] samples(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).toArray();
    }

    /** A wall-clock row without warm-up or GC telemetry. */
    static LatencyRow wallClock(long[] samples, int operationsPerIteration, String timed, boolean validationInWindow) {
        return new LatencyRow(samples, 0, operationsPerIteration, WALL_CLOCK + "; " + timed, validationInWindow,
                NO_GC_TELEMETRY);
    }

    /**
     * Records {@code <row>.medianNanos}, {@code <row>.p95Nanos}, every sampling field and {@code <row>.excludedSamples}
     * ({@code 0}, or the count and the reasons).
     */
    void describe(String row, Map<String, String> metadata) {
        var available = samples.length > 0;
        var none = "unavailable (no successful sample)";
        metadata.put(row + ".medianNanos", available ? Long.toString(percentile(samples, 50)) : none);
        metadata.put(row + ".p95Nanos", available ? Long.toString(percentile(samples, 95)) : none);
        metadata.put(row + ".warmupIterations", Integer.toString(warmupIterations));
        metadata.put(row + ".measurementIterations", Integer.toString(samples.length));
        metadata.put(row + ".operationsPerIteration", Integer.toString(operationsPerIteration));
        metadata.put(row + ".measurementMethod", measurementMethod);
        metadata.put(row + ".validationInWindow", Boolean.toString(validationInWindow));
        metadata.put(row + ".gcTelemetryScope", gcTelemetryScope);
        metadata.put(row + ".excludedSamples", excluded.isEmpty() ? "0"
                : excluded.size() + ": " + String.join("; ", excluded));
    }

    /** Returns the nearest-rank {@code percent} percentile of non-empty {@code values}. */
    static long percentile(long[] values, int percent) {
        var sorted = values.clone();
        Arrays.sort(sorted);
        var index = (int) Math.ceil(percent / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, index)];
    }
}
