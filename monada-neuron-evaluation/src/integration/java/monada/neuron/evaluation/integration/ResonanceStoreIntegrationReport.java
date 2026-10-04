package monada.neuron.evaluation.integration;

import monada.neuron.evaluation.integration.ResonanceStoreIntegrationEvaluation.Check;
import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport;

import java.util.List;
import java.util.Map;

/**
 * Diff-friendly JSON and Markdown rendering of the integration evaluation.
 *
 * <p>Semantic verdicts and metadata are stable across runs; only the embedded {@link EvaluationReport}
 * measurements and timestamp vary by machine. Rendering is shared with the other checked evaluations
 * through {@link CheckedEvaluationReport}.
 */
public record ResonanceStoreIntegrationReport(
        EvaluationReport evaluation,
        List<Check> checks,
        Map<String, String> metadata) {

    public static final String TITLE = "Monada Neuron + Resonance Store Integration Report";

    private static final String METADATA_HEADING = "Integration Metadata";

    /** Snapshots checks and metadata (metadata sorted by key). */
    public ResonanceStoreIntegrationReport {
        var shared = new CheckedEvaluationReport(TITLE, METADATA_HEADING, evaluation, checks, metadata);
        checks = List.copyOf(checks);
        metadata = shared.metadata();
    }

    /** Returns whether every semantic check passed. */
    public boolean allPassed() {
        return shared().allPassed();
    }

    /** Renders verdicts and metadata followed by the full measurement report. */
    public String toJson() {
        return shared().toJson();
    }

    /** Renders a human-readable report with verdicts before measurements. */
    public String toMarkdown() {
        return shared().toMarkdown();
    }

    private CheckedEvaluationReport shared() {
        return new CheckedEvaluationReport(TITLE, METADATA_HEADING, evaluation, checks, metadata);
    }
}
