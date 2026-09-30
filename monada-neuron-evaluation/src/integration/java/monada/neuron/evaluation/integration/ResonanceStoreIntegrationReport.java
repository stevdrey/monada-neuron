package monada.neuron.evaluation.integration;

import monada.neuron.evaluation.integration.ResonanceStoreIntegrationEvaluation.Check;
import monada.neuron.evaluation.metrics.EvaluationReport;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Diff-friendly JSON and Markdown rendering of the integration evaluation.
 *
 * <p>Semantic verdicts and metadata are stable across runs; only the embedded {@link EvaluationReport}
 * measurements and timestamp vary by machine.
 */
public record ResonanceStoreIntegrationReport(
        EvaluationReport evaluation,
        List<Check> checks,
        Map<String, String> metadata) {

    public static final String TITLE = "Monada Neuron + Resonance Store Integration Report";

    public ResonanceStoreIntegrationReport {
        Objects.requireNonNull(evaluation, "evaluation must not be null");
        checks = List.copyOf(checks);
        var sorted = new TreeMap<String, String>();
        metadata.forEach((key, value) -> sorted.put(
                Objects.requireNonNull(key, "metadata key must not be null"),
                Objects.requireNonNull(value, "metadata value must not be null")));
        metadata = Collections.unmodifiableSortedMap(sorted);
    }

    /** Returns whether every semantic check passed. */
    public boolean allPassed() {
        return checks.stream().allMatch(Check::passed);
    }

    /** Renders verdicts and metadata followed by the full measurement report. */
    public String toJson() {
        var sb = new StringBuilder("{\n");
        sb.append("  \"title\": \"").append(escape(TITLE)).append("\",\n");
        sb.append("  \"allChecksPassed\": ").append(allPassed()).append(",\n");
        sb.append("  \"checks\": [\n");
        for (var i = 0; i < checks.size(); i++) {
            var check = checks.get(i);
            sb.append("    { \"name\": \"").append(escape(check.name()))
                    .append("\", \"passed\": ").append(check.passed())
                    .append(", \"detail\": \"").append(escape(check.detail())).append("\" }");
            sb.append(i < checks.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        sb.append("  \"metadata\": {\n");
        var index = 0;
        for (var entry : metadata.entrySet()) {
            sb.append("    \"").append(escape(entry.getKey())).append("\": \"").append(escape(entry.getValue())).append("\"");
            sb.append(++index < metadata.size() ? ",\n" : "\n");
        }
        sb.append("  },\n");
        sb.append("  \"evaluation\": ").append(evaluation.toJson().stripTrailing()).append("\n}\n");
        return sb.toString();
    }

    /** Renders a human-readable report with verdicts before measurements. */
    public String toMarkdown() {
        var section = new StringBuilder();
        section.append("## Semantic Checks\n\n");
        section.append("Overall: **").append(allPassed() ? "PASS" : "FAIL").append("**\n\n");
        section.append("| Check | Result | Detail |\n| :--- | :--- | :--- |\n");
        for (var check : checks) {
            section.append("| `").append(check.name()).append("` | ")
                    .append(check.passed() ? "PASS" : "FAIL").append(" | ")
                    .append(escapeCell(check.detail())).append(" |\n");
        }
        section.append("\n## Integration Metadata\n\n| Key | Value |\n| :--- | :--- |\n");
        metadata.forEach((key, value) ->
                section.append("| ").append(key).append(" | `").append(escapeCell(value)).append("` |\n"));
        section.append("\nLatency, allocation, and GC values below are machine-specific exploratory diagnostics,"
                + " not pass/fail gates.\n\n");

        var body = evaluation.toMarkdown();
        var marker = "## Run Configuration";
        var at = body.indexOf(marker);
        return at < 0 ? body + section : body.substring(0, at) + section + body.substring(at);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String escapeCell(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
