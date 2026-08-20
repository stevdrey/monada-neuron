package monada.neuron.evaluation.metrics;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable results of a single benchmark experiment run.
 */
public record BenchmarkRunResult(
        String benchmarkName,
        String workloadScale,
        int iterations,
        LatencyDistribution latency,
        AllocationMetrics allocation,
        Map<String, String> diagnostics) {

    public BenchmarkRunResult {
        Objects.requireNonNull(benchmarkName, "benchmarkName must not be null");
        Objects.requireNonNull(workloadScale, "workloadScale must not be null");
        Objects.requireNonNull(latency, "latency must not be null");
        Objects.requireNonNull(allocation, "allocation must not be null");
        diagnostics = Map.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }
}
