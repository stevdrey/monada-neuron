package monada.neuron.evaluation.metrics;

/**
 * Immutable allocation and garbage collection metrics captured across benchmark iterations.
 */
public record AllocationMetrics(
        long totalAllocatedBytes,
        double bytesPerOp,
        long gcCountDelta,
        long gcTimeMillisDelta,
        long heapUsedBeforeBytes,
        long heapUsedAfterBytes) {

    public static AllocationMetrics empty() {
        return new AllocationMetrics(0L, 0.0, 0L, 0L, 0L, 0L);
    }
}
