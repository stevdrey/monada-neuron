package monada.neuron.evaluation.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Metric collector harness that executes target workloads, collects latency distributions,
 * allocation telemetry, and garbage collection diagnostics.
 *
 * <p>Note on telemetry isolation:
 * <ul>
 *   <li><b>Latency and Thread Allocation:</b> Isolated strictly to the {@code workload.run()} execution interval.
 *       Any per-iteration setup work executed via {@code iterationSetup} is excluded from measured latency and
 *       thread-allocated byte deltas.</li>
 *   <li><b>Garbage Collection Deltas:</b> JVM GC MXBeans report whole-JVM cumulative statistics, which cover the
 *       entire measurement phase (including any GC triggered during per-iteration setup).</li>
 * </ul>
 */

public final class EvaluationMetricsCollector {

    private final com.sun.management.ThreadMXBean sunThreadMXBean;
    private final boolean threadAllocatedMemorySupported;

    public EvaluationMetricsCollector() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (threadBean instanceof com.sun.management.ThreadMXBean sunBean) {
            this.sunThreadMXBean = sunBean;
            boolean supported = sunBean.isThreadAllocatedMemorySupported();
            if (supported && !sunBean.isThreadAllocatedMemoryEnabled()) {
                try {
                    sunBean.setThreadAllocatedMemoryEnabled(true);
                } catch (SecurityException | UnsupportedOperationException ignored) {
                    // Fallback if enabling is not permitted
                }
            }
            this.threadAllocatedMemorySupported = supported && sunBean.isThreadAllocatedMemoryEnabled();
        } else {
            this.sunThreadMXBean = null;
            this.threadAllocatedMemorySupported = false;
        }
    }

    /**
     * Executes a benchmark runnable across warm-up and measurement iterations (1 op per iteration).
     */
    public BenchmarkRunResult measure(
            String benchmarkName,
            String workloadScale,
            int warmupIterations,
            int measurementIterations,
            Runnable workload,
            Map<String, String> diagnostics) {
        return measure(benchmarkName, workloadScale, warmupIterations, measurementIterations, 1, null, workload, diagnostics);
    }

    /**
     * Executes a benchmark runnable across warm-up and measurement iterations with domain operation count.
     */
    public BenchmarkRunResult measure(
            String benchmarkName,
            String workloadScale,
            int warmupIterations,
            int measurementIterations,
            int operationsPerIteration,
            Runnable workload,
            Map<String, String> diagnostics) {
        return measure(benchmarkName, workloadScale, warmupIterations, measurementIterations, operationsPerIteration, null, workload, diagnostics);
    }

    /**
     * Executes a benchmark runnable across warm-up and measurement iterations with per-iteration setup,
     * ensuring setup operations and allocations are excluded from timing and allocation telemetry.
     */
    public BenchmarkRunResult measure(
            String benchmarkName,
            String workloadScale,
            int warmupIterations,
            int measurementIterations,
            int operationsPerIteration,
            Runnable iterationSetup,
            Runnable workload,
            Map<String, String> diagnostics) {
        Objects.requireNonNull(benchmarkName, "benchmarkName must not be null");
        Objects.requireNonNull(workloadScale, "workloadScale must not be null");
        Objects.requireNonNull(workload, "workload must not be null");
        if (warmupIterations < 0) {
            throw new IllegalArgumentException("warmupIterations must be non-negative");
        }
        if (measurementIterations <= 0) {
            throw new IllegalArgumentException("measurementIterations must be positive");
        }
        if (operationsPerIteration <= 0) {
            throw new IllegalArgumentException("operationsPerIteration must be positive, got: " + operationsPerIteration);
        }

        // Warm-up phase
        for (int i = 0; i < warmupIterations; i++) {
            if (iterationSetup != null) {
                iterationSetup.run();
            }
            workload.run();
        }

        // Prepare GC baseline
        long gcCountBefore = totalGcCount();
        long gcTimeBefore = totalGcTime();
        long heapBefore = currentHeapUsedBytes();
        OptionalLong residentSetBefore = currentResidentSetBytes();

        long[] sampleNanos = new long[measurementIterations];
        long totalAllocatedInIntervals = 0L;
        boolean trackingSucceeded = threadAllocatedMemorySupported;

        // Measurement phase
        for (int i = 0; i < measurementIterations; i++) {
            if (iterationSetup != null) {
                iterationSetup.run();
            }
            long threadStart = currentThreadAllocatedBytes();
            long start = System.nanoTime();
            workload.run();
            long elapsed = System.nanoTime() - start;
            long threadEnd = currentThreadAllocatedBytes();
            sampleNanos[i] = elapsed;

            if (trackingSucceeded && threadEnd >= threadStart) {
                totalAllocatedInIntervals += (threadEnd - threadStart);
            } else {
                trackingSucceeded = false;
            }
        }

        long gcCountAfter = totalGcCount();
        long gcTimeAfter = totalGcTime();
        long heapAfter = currentHeapUsedBytes();
        OptionalLong residentSetAfter = currentResidentSetBytes();

        LatencyDistribution latency = LatencyDistribution.fromSamples(sampleNanos, operationsPerIteration);

        long gcCountDelta = Math.max(0L, gcCountAfter - gcCountBefore);
        long gcTimeDelta = Math.max(0L, gcTimeAfter - gcTimeBefore);
        ProcessResidentSetMetrics residentSet = ProcessResidentSetMetrics.fromReadings(
                residentSetBefore,
                residentSetAfter);

        AllocationMetrics allocation;
        if (trackingSucceeded && threadAllocatedMemorySupported) {
            double bytesPerOp = (double) totalAllocatedInIntervals / ((long) measurementIterations * operationsPerIteration);
            allocation = AllocationMetrics.ofThreadAllocated(
                    totalAllocatedInIntervals,
                    bytesPerOp,
                    gcCountDelta,
                    gcTimeDelta,
                    heapBefore,
                    heapAfter,
                    residentSet);
        } else {
            allocation = AllocationMetrics.unavailable(
                    gcCountDelta,
                    gcTimeDelta,
                    heapBefore,
                    heapAfter,
                    residentSet);
        }

        return new BenchmarkRunResult(
                benchmarkName,
                workloadScale,
                measurementIterations,
                latency,
                allocation,
                diagnostics);

    }

    private long currentThreadAllocatedBytes() {
        if (threadAllocatedMemorySupported && sunThreadMXBean != null) {
            try {
                return sunThreadMXBean.getCurrentThreadAllocatedBytes();
            } catch (Exception e) {
                return 0L;
            }
        }
        return 0L;
    }

    private long currentHeapUsedBytes() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private OptionalLong currentResidentSetBytes() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            return OptionalLong.empty();
        }
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (!line.startsWith("VmRSS:")) {
                    continue;
                }
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 2) {
                    return OptionalLong.empty();
                }
                return OptionalLong.of(Math.multiplyExact(Long.parseLong(tokens[1]), 1024L));
            }
        } catch (IOException | NumberFormatException | ArithmeticException ignored) {
            // RSS is optional platform telemetry; do not substitute heap usage when unavailable.
        }
        return OptionalLong.empty();
    }

    private long totalGcCount() {
        long count = 0L;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (var bean : beans) {
            long c = bean.getCollectionCount();
            if (c > 0) {
                count += c;
            }
        }
        return count;
    }

    private long totalGcTime() {
        long time = 0L;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (var bean : beans) {
            long t = bean.getCollectionTime();
            if (t > 0) {
                time += t;
            }
        }
        return time;
    }
}
