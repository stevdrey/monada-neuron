package monada.neuron.evaluation.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Aggregated baseline evaluation report holding environment metadata, run configuration,
 * and all benchmark run results.
 */
public record EvaluationReport(
        Instant timestamp,
        EnvironmentMetadata environment,
        RunConfiguration runConfiguration,
        List<BenchmarkRunResult> results) {

    public record RunConfiguration(
            long seed,
            boolean quickMode,
            int defaultWarmupIterations,
            int defaultMeasurementIterations,
            String operationsPerIterationPolicy) {

        public static RunConfiguration defaultFull(long seed) {
            return new RunConfiguration(
                    seed,
                    false,
                    5,
                    20,
                    "Domain-scaled for batch resonance (N pairs); 1 op/iter for graph, Aeon, and cycle workloads");
        }

        public static RunConfiguration defaultQuick(long seed) {
            return new RunConfiguration(
                    seed,
                    true,
                    2,
                    5,
                    "Domain-scaled for batch resonance (N pairs); 1 op/iter for graph, Aeon, and cycle workloads");
        }
    }

    public EvaluationReport {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(runConfiguration, "runConfiguration must not be null");
        results = List.copyOf(Objects.requireNonNull(results, "results must not be null"));
    }

    public EvaluationReport(Instant timestamp, EnvironmentMetadata environment, List<BenchmarkRunResult> results) {
        this(timestamp, environment, RunConfiguration.defaultFull(42L), results);
    }

    /** Formats the evaluation report as a machine-readable JSON string. */
    public String toJson() {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"timestamp\": \"").append(timestamp.toString()).append("\",\n");
        sb.append("  \"runConfiguration\": {\n");
        sb.append("    \"seed\": ").append(runConfiguration.seed()).append(",\n");
        sb.append("    \"quickMode\": ").append(runConfiguration.quickMode()).append(",\n");
        sb.append("    \"defaultWarmupIterations\": ").append(runConfiguration.defaultWarmupIterations()).append(",\n");
        sb.append("    \"defaultMeasurementIterations\": ").append(runConfiguration.defaultMeasurementIterations()).append(",\n");
        sb.append("    \"operationsPerIterationPolicy\": \"").append(escapeJson(runConfiguration.operationsPerIterationPolicy())).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"environment\": {\n");
        sb.append("    \"javaVersion\": \"").append(escapeJson(environment.javaVersion())).append("\",\n");
        sb.append("    \"javaVendor\": \"").append(escapeJson(environment.javaVendor())).append("\",\n");
        sb.append("    \"jvmName\": \"").append(escapeJson(environment.jvmName())).append("\",\n");
        sb.append("    \"osName\": \"").append(escapeJson(environment.osName())).append("\",\n");
        sb.append("    \"osArch\": \"").append(escapeJson(environment.osArch())).append("\",\n");
        sb.append("    \"availableProcessors\": ").append(environment.availableProcessors()).append(",\n");
        sb.append("    \"maxMemoryBytes\": ").append(environment.maxMemoryBytes()).append(",\n");
        sb.append("    \"totalMemoryBytes\": ").append(environment.totalMemoryBytes()).append(",\n");
        sb.append("    \"garbageCollectors\": [");
        for (int i = 0; i < environment.garbageCollectors().size(); i++) {
            sb.append("\"").append(escapeJson(environment.garbageCollectors().get(i))).append("\"");
            if (i < environment.garbageCollectors().size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("],\n");
        sb.append("    \"jvmArguments\": [");
        for (int i = 0; i < environment.jvmArguments().size(); i++) {
            sb.append("\"").append(escapeJson(environment.jvmArguments().get(i))).append("\"");
            if (i < environment.jvmArguments().size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("]\n");
        sb.append("  },\n");
        sb.append("  \"results\": [\n");

        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append("    {\n");
            sb.append("      \"benchmarkName\": \"").append(escapeJson(r.benchmarkName())).append("\",\n");
            sb.append("      \"workloadScale\": \"").append(escapeJson(r.workloadScale())).append("\",\n");
            sb.append("      \"iterations\": ").append(r.iterations()).append(",\n");
            sb.append("      \"latency\": {\n");
            sb.append(String.format(Locale.ROOT, "        \"minNanos\": %.2f,\n", r.latency().minNanos()));
            sb.append(String.format(Locale.ROOT, "        \"medianNanos\": %.2f,\n", r.latency().medianNanos()));
            sb.append(String.format(Locale.ROOT, "        \"p90Nanos\": %.2f,\n", r.latency().p90Nanos()));
            sb.append(String.format(Locale.ROOT, "        \"p95Nanos\": %.2f,\n", r.latency().p95Nanos()));
            sb.append(String.format(Locale.ROOT, "        \"p99Nanos\": %.2f,\n", r.latency().p99Nanos()));
            sb.append(String.format(Locale.ROOT, "        \"maxNanos\": %.2f,\n", r.latency().maxNanos()));
            sb.append(String.format(Locale.ROOT, "        \"meanNanos\": %.2f,\n", r.latency().meanNanos()));
            sb.append(String.format(Locale.ROOT, "        \"stdDevNanos\": %.2f,\n", r.latency().stdDevNanos()));
            sb.append(String.format(Locale.ROOT, "        \"throughputOpsPerSec\": %.2f\n", r.latency().throughputOpsPerSec()));
            sb.append("      },\n");
            sb.append("      \"allocation\": {\n");
            sb.append("        \"totalAllocatedBytes\": ").append(r.allocation().totalAllocatedBytes()).append(",\n");
            sb.append(String.format(Locale.ROOT, "        \"bytesPerOp\": %.2f,\n", r.allocation().bytesPerOp()));
            sb.append("        \"gcCountDelta\": ").append(r.allocation().gcCountDelta()).append(",\n");
            sb.append("        \"gcTimeMillisDelta\": ").append(r.allocation().gcTimeMillisDelta()).append("\n");
            sb.append("      },\n");
            sb.append("      \"diagnostics\": {\n");
            var sortedDiagnostics = new TreeMap<>(r.diagnostics());
            int dIndex = 0;
            for (var entry : sortedDiagnostics.entrySet()) {
                sb.append("        \"").append(escapeJson(entry.getKey())).append("\": \"")
                        .append(escapeJson(entry.getValue())).append("\"");
                if (++dIndex < sortedDiagnostics.size()) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("      }\n");
            sb.append("    }");
            if (i < results.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Formats the evaluation report as a human-readable Markdown document. */
    public String toMarkdown() {
        var sb = new StringBuilder();
        sb.append("# Monada Neuron Cognitive Baseline Report\n\n");
        sb.append("**Generated At:** `").append(timestamp.toString()).append("`\n\n");
        sb.append("## Run Configuration\n\n");
        sb.append("| Setting | Value |\n");
        sb.append("| :--- | :--- |\n");
        sb.append("| **Deterministic Seed** | `").append(runConfiguration.seed()).append("` |\n");
        sb.append("| **Execution Mode** | `").append(runConfiguration.quickMode() ? "Quick (Smoke Mode)" : "Full Baseline").append("` |\n");
        sb.append("| **Default Warmup Iterations** | `").append(runConfiguration.defaultWarmupIterations()).append("` |\n");
        sb.append("| **Default Measurement Iterations** | `").append(runConfiguration.defaultMeasurementIterations()).append("` |\n");
        sb.append("| **Operation Count Semantics** | `").append(runConfiguration.operationsPerIterationPolicy()).append("` |\n\n");

        sb.append("## Environment Metadata\n\n");
        sb.append("| Property | Value |\n");
        sb.append("| :--- | :--- |\n");
        sb.append("| **Java Version** | `").append(environment.javaVersion()).append("` |\n");
        sb.append("| **Java Vendor** | `").append(environment.javaVendor()).append("` |\n");
        sb.append("| **JVM Name** | `").append(environment.jvmName()).append("` |\n");
        sb.append("| **OS** | `").append(environment.osName()).append(" (").append(environment.osArch()).append(")` |\n");
        sb.append("| **Processors** | `").append(environment.availableProcessors()).append("` |\n");
        sb.append(String.format(Locale.ROOT, "| **Max Heap** | `%.2f MB` |\n", environment.maxMemoryBytes() / (1024.0 * 1024.0)));
        sb.append("| **Active GCs** | `").append(String.join(", ", environment.garbageCollectors())).append("` |\n\n");

        sb.append("## Workload Benchmark Results\n\n");
        sb.append("| Benchmark | Scale | Mean Latency | Median (p50) | p95 | p99 | Throughput | Alloc / Op |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");

        for (var r : results) {
            sb.append(String.format(
                    Locale.ROOT,
                    "| `%s` | %s | %s | %s | %s | %s | %,.0f ops/s | %s |\n",
                    r.benchmarkName(),
                    r.workloadScale(),
                    formatNanos(r.latency().meanNanos()),
                    formatNanos(r.latency().medianNanos()),
                    formatNanos(r.latency().p95Nanos()),
                    formatNanos(r.latency().p99Nanos()),
                    r.latency().throughputOpsPerSec(),
                    formatBytes(r.allocation().bytesPerOp())));
        }

        sb.append("\n## Diagnostic Details\n\n");
        for (var r : results) {
            if (!r.diagnostics().isEmpty()) {
                sb.append("### `").append(r.benchmarkName()).append("` (").append(r.workloadScale()).append(")\n\n");
                sb.append("| Key | Value |\n");
                sb.append("| :--- | :--- |\n");
                var sortedDiagnostics = new TreeMap<>(r.diagnostics());
                for (var entry : sortedDiagnostics.entrySet()) {
                    sb.append("| ").append(entry.getKey()).append(" | `").append(entry.getValue()).append("` |\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private static String formatNanos(double nanos) {
        if (nanos < 1_000.0) {
            return String.format(Locale.ROOT, "%.1f ns", nanos);
        } else if (nanos < 1_000_000.0) {
            return String.format(Locale.ROOT, "%.2f µs", nanos / 1_000.0);
        } else if (nanos < 1_000_000_000.0) {
            return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
        } else {
            return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0);
        }
    }

    private static String formatBytes(double bytes) {
        if (bytes < 1024.0) {
            return String.format(Locale.ROOT, "%.1f B", bytes);
        } else if (bytes < 1024.0 * 1024.0) {
            return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
        } else {
            return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
