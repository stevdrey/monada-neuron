package monada.neuron.evaluation.metrics;

import java.util.Objects;

/**
 * Immutable allocation and garbage collection metrics captured across benchmark iterations.
 */
public record AllocationMetrics(
        AllocationSource source,
        long totalAllocatedBytes,
        double bytesPerOp,
        long gcCountDelta,
        long gcTimeMillisDelta,
        long heapUsedBeforeBytes,
        long heapUsedAfterBytes) {

    public enum AllocationSource {
        THREAD_MX_BEAN,
        UNAVAILABLE
    }

    public AllocationMetrics {
        Objects.requireNonNull(source, "source must not be null");
    }

    public static AllocationMetrics ofThreadAllocated(
            long totalAllocatedBytes,
            double bytesPerOp,
            long gcCountDelta,
            long gcTimeMillisDelta,
            long heapUsedBeforeBytes,
            long heapUsedAfterBytes) {
        return new AllocationMetrics(
                AllocationSource.THREAD_MX_BEAN,
                totalAllocatedBytes,
                bytesPerOp,
                gcCountDelta,
                gcTimeMillisDelta,
                heapUsedBeforeBytes,
                heapUsedAfterBytes);
    }

    public static AllocationMetrics unavailable(
            long gcCountDelta,
            long gcTimeMillisDelta,
            long heapUsedBeforeBytes,
            long heapUsedAfterBytes) {
        return new AllocationMetrics(
                AllocationSource.UNAVAILABLE,
                -1L,
                -1.0,
                gcCountDelta,
                gcTimeMillisDelta,
                heapUsedBeforeBytes,
                heapUsedAfterBytes);
    }

    public static AllocationMetrics empty() {
        return ofThreadAllocated(0L, 0.0, 0L, 0L, 0L, 0L);
    }
}
