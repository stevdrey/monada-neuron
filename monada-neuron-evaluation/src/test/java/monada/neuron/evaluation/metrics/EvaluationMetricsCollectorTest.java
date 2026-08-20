package monada.neuron.evaluation.metrics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationMetricsCollectorTest {

    @Test
    void calculatesLatencyDistributionAccurately() {
        long[] samples = new long[]{100, 200, 300, 400, 500};
        LatencyDistribution dist = LatencyDistribution.fromSamples(samples);

        assertAll(
                () -> assertEquals(5, dist.sampleCount()),
                () -> assertEquals(100.0, dist.minNanos()),
                () -> assertEquals(300.0, dist.medianNanos()),
                () -> assertEquals(500.0, dist.maxNanos()),
                () -> assertEquals(300.0, dist.meanNanos()),
                () -> assertTrue(dist.throughputOpsPerSec() > 0));
    }

    @Test
    void scalesThroughputWithOperationsPerIteration() {
        long[] samples = new long[]{1_000_000}; // 1 ms mean
        LatencyDistribution single = LatencyDistribution.fromSamples(samples, 1);
        LatencyDistribution batch = LatencyDistribution.fromSamples(samples, 100);

        assertEquals(1_000.0, single.throughputOpsPerSec(), 1e-3);
        assertEquals(100_000.0, batch.throughputOpsPerSec(), 1e-3);
    }

    @Test
    void measuresWorkloadExecution() {
        var collector = new EvaluationMetricsCollector();
        BenchmarkRunResult result = collector.measure(
                "TestBenchmark",
                "10 items",
                2,
                5,
                () -> {
                    long x = 0;
                    for (int i = 0; i < 1000; i++) {
                        x += i;
                    }
                },
                Map.of("key", "value"));

        assertAll(
                () -> assertEquals("TestBenchmark", result.benchmarkName()),
                () -> assertEquals("10 items", result.workloadScale()),
                () -> assertEquals(5, result.iterations()),
                () -> assertTrue(result.latency().meanNanos() > 0),
                () -> assertEquals("value", result.diagnostics().get("key")));
    }

    @Test
    void measuresWorkloadExecutionWithExplicitOperationsPerIteration() {
        var collector = new EvaluationMetricsCollector();
        BenchmarkRunResult result = collector.measure(
                "BatchTestBenchmark",
                "100 items",
                2,
                5,
                100,
                () -> {
                    long x = 0;
                    for (int i = 0; i < 100; i++) {
                        x += i;
                    }
                },
                Map.of("batchSize", "100"));

        assertAll(
                () -> assertEquals("BatchTestBenchmark", result.benchmarkName()),
                () -> assertEquals("100 items", result.workloadScale()),
                () -> assertEquals(5, result.iterations()),
                () -> assertTrue(result.latency().throughputOpsPerSec() > 0));
    }

    @Test
    void serializesReportToJsonAndMarkdown() {
        var env = EnvironmentMetadata.current();
        var latency = new LatencyDistribution(5, 10.0, 20.0, 25.0, 28.0, 29.0, 30.0, 20.0, 5.0, 50_000_000.0);
        var allocation = new AllocationMetrics(1024L, 204.8, 0L, 0L, 1000L, 2024L);
        var run = new BenchmarkRunResult("SampleBench", "scale-1", 5, latency, allocation, Map.of("tag", "test"));

        var report = new EvaluationReport(Instant.now(), env, List.of(run));
        String json = report.toJson();
        String markdown = report.toMarkdown();

        assertNotNull(json);
        assertTrue(json.contains("\"benchmarkName\": \"SampleBench\""));
        assertTrue(json.contains("\"throughputOpsPerSec\":"));

        assertNotNull(markdown);
        assertTrue(markdown.contains("`SampleBench`"));
        assertTrue(markdown.contains("Environment Metadata"));
    }
}
