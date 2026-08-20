package monada.neuron.evaluation.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Metric collector harness that executes target workloads, collects latency distributions,
 * allocation telemetry, and garbage collection diagnostics.
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
     *
     * @param benchmarkName name of the benchmark
     * @param workloadScale description of workload size/scale
     * @param warmupIterations number of warm-up runs (not recorded)
     * @param measurementIterations number of measurement runs (recorded)
     * @param workload the workload to benchmark
     * @param diagnostics additional diagnostic metadata
     * @return benchmark run result
     */
    public BenchmarkRunResult measure(
            String benchmarkName,
            String workloadScale,
            int warmupIterations,
            int measurementIterations,
            Runnable workload,
            Map<String, String> diagnostics) {
        return measure(benchmarkName, workloadScale, warmupIterations, measurementIterations, 1, workload, diagnostics);
    }

    /**
     * Executes a benchmark runnable across warm-up and measurement iterations with domain operation count.
     *
     * @param benchmarkName name of the benchmark
     * @param workloadScale description of workload size/scale
     * @param warmupIterations number of warm-up runs (not recorded)
     * @param measurementIterations number of measurement runs (recorded)
     * @param operationsPerIteration number of domain operations executed within each measured iteration
     * @param workload the workload to benchmark
     * @param diagnostics additional diagnostic metadata
     * @return benchmark run result
     */
    public BenchmarkRunResult measure(
            String benchmarkName,
            String workloadScale,
            int warmupIterations,
            int measurementIterations,
            int operationsPerIteration,
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
            workload.run();
        }

        // Prepare GC baseline
        long gcCountBefore = totalGcCount();
        long gcTimeBefore = totalGcTime();
        long threadAllocatedBefore = currentThreadAllocatedBytes();
        long heapBefore = currentHeapUsedBytes();

        long[] sampleNanos = new long[measurementIterations];

        // Measurement phase
        for (int i = 0; i < measurementIterations; i++) {
            long start = System.nanoTime();
            workload.run();
            long elapsed = System.nanoTime() - start;
            sampleNanos[i] = elapsed;
        }

        long threadAllocatedAfter = currentThreadAllocatedBytes();
        long gcCountAfter = totalGcCount();
        long gcTimeAfter = totalGcTime();
        long heapAfter = currentHeapUsedBytes();

        LatencyDistribution latency = LatencyDistribution.fromSamples(sampleNanos, operationsPerIteration);

        long totalAllocated = 0L;
        if (threadAllocatedMemorySupported && threadAllocatedAfter >= threadAllocatedBefore) {
            totalAllocated = threadAllocatedAfter - threadAllocatedBefore;
        } else if (heapAfter > heapBefore) {
            totalAllocated = heapAfter - heapBefore;
        }

        double bytesPerOp = (double) totalAllocated / ((long) measurementIterations * operationsPerIteration);
        long gcCountDelta = Math.max(0L, gcCountAfter - gcCountBefore);
        long gcTimeDelta = Math.max(0L, gcTimeAfter - gcTimeBefore);

        AllocationMetrics allocation = new AllocationMetrics(
                totalAllocated,
                bytesPerOp,
                gcCountDelta,
                gcTimeDelta,
                heapBefore,
                heapAfter);

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
