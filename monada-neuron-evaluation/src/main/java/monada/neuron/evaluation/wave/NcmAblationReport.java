package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Report of the consolidated NCM ablation (issue #130): semantic checks, metadata (including the go/no-go verdict),
 * the per-workload and per-arm results, then the resource measurements, run configuration and environment. Rendering
 * is shared through {@link CheckedEvaluationReport}.
 *
 * @param evaluation run configuration, environment and the resource measurements
 * @param checks semantic checks
 * @param metadata protocol, selections, gates and verdict
 * @param resultsJson results as a JSON value
 * @param resultsMarkdown results as Markdown tables
 */
public record NcmAblationReport(EvaluationReport evaluation, List<EvaluationCheck> checks,
                               Map<String, String> metadata, String resultsJson, String resultsMarkdown) {

    /** Report title. */
    public static final String TITLE = "Monada Neuron NCM Ablation and Go/No-Go Report";

    private static final String METADATA_HEADING = "Protocol, Selections and Verdict";

    /** Snapshots checks and metadata (metadata sorted by key). */
    public NcmAblationReport {
        var shared = new CheckedEvaluationReport(TITLE, METADATA_HEADING, evaluation, checks, metadata);
        checks = List.copyOf(checks);
        metadata = shared.metadata();
        Objects.requireNonNull(resultsJson, "resultsJson must not be null");
        Objects.requireNonNull(resultsMarkdown, "resultsMarkdown must not be null");
    }

    /** Returns whether every semantic check passed; the verdict is metadata, not a check. */
    public boolean allPassed() {
        return shared().allPassed();
    }

    /** Renders checks, metadata and the results, then the measurements, run configuration and environment. */
    public String toJson() {
        return shared().toJson(Map.of("ablation", resultsJson));
    }

    /** Renders a human-readable report with checks and results before the measurements and environment. */
    public String toMarkdown() {
        var body = shared().toMarkdown();
        var marker = "## Run Configuration";
        var at = body.indexOf(marker);
        return at < 0 ? body + resultsMarkdown : body.substring(0, at) + resultsMarkdown + "\n" + body.substring(at);
    }

    private CheckedEvaluationReport shared() {
        return new CheckedEvaluationReport(TITLE, METADATA_HEADING, evaluation, checks, metadata);
    }
}
