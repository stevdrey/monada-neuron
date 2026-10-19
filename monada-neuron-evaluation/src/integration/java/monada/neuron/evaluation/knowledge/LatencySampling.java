package monada.neuron.evaluation.knowledge;

import java.util.Map;
import java.util.Objects;

/**
 * How one latency row of a knowledge report was sampled, recorded next to it as the protocol requires ("Resources
 * (L6)"), with the field names of the integration evaluation. Rows with different sampling are not comparable.
 *
 * @param warmupIterations unmeasured executions before the measured ones
 * @param measurementIterations measured samples the row aggregates
 * @param operationsPerIteration operations inside one measured sample
 * @param measurementMethod how a sample was timed
 * @param validationInWindow whether result validation (mapping, hashing) ran inside the timed window
 * @param gcTelemetryScope which GC or allocation telemetry was collected for the row
 */
record LatencySampling(int warmupIterations, int measurementIterations, int operationsPerIteration,
        String measurementMethod, boolean validationInWindow, String gcTelemetryScope) {

    /** Measurement method of every knowledge latency row. */
    static final String WALL_CLOCK = "System.nanoTime wall clock around the timed operation; not JMH and not"
            + " EvaluationMetricsCollector";

    /** GC telemetry scope of every knowledge latency row. */
    static final String NO_GC_TELEMETRY = "none collected";

    LatencySampling {
        Objects.requireNonNull(measurementMethod, "measurementMethod must not be null");
        Objects.requireNonNull(gcTelemetryScope, "gcTelemetryScope must not be null");
    }

    /** A single wall-clock sample with no GC telemetry. */
    static LatencySampling single(int warmupIterations, boolean validationInWindow) {
        return new LatencySampling(warmupIterations, 1, 1, WALL_CLOCK, validationInWindow, NO_GC_TELEMETRY);
    }

    /** Records every field as {@code <row>.<field>}. */
    void describe(String row, Map<String, String> metadata) {
        metadata.put(row + ".warmupIterations", Integer.toString(warmupIterations));
        metadata.put(row + ".measurementIterations", Integer.toString(measurementIterations));
        metadata.put(row + ".operationsPerIteration", Integer.toString(operationsPerIteration));
        metadata.put(row + ".measurementMethod", measurementMethod);
        metadata.put(row + ".validationInWindow", Boolean.toString(validationInWindow));
        metadata.put(row + ".gcTelemetryScope", gcTelemetryScope);
    }
}
