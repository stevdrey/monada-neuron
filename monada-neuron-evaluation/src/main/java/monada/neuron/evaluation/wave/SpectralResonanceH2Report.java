package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Report of the H2 evaluation: semantic checks, metadata (including the G2 verdict) and the per-repeat results,
 * followed by the run configuration and environment. Rendering is shared through {@link CheckedEvaluationReport}.
 *
 * @param evaluation run configuration and environment
 * @param checks semantic checks
 * @param metadata protocol, results summary and verdict
 * @param resultsJson per-repeat results as a JSON value
 * @param resultsMarkdown per-repeat results as Markdown tables
 */
public record SpectralResonanceH2Report(EvaluationReport evaluation, List<EvaluationCheck> checks,
                                        Map<String, String> metadata, String resultsJson, String resultsMarkdown) {

    /** Report title. */
    public static final String TITLE = "Monada Neuron Spectral Resonance H2 Report";

    private static final String METADATA_HEADING = "H2 Protocol and Verdict";

    /** Snapshots checks and metadata (metadata sorted by key). */
    public SpectralResonanceH2Report {
        var shared = new CheckedEvaluationReport(TITLE, METADATA_HEADING, evaluation, checks, metadata);
        checks = List.copyOf(checks);
        metadata = shared.metadata();
        Objects.requireNonNull(resultsJson, "resultsJson must not be null");
        Objects.requireNonNull(resultsMarkdown, "resultsMarkdown must not be null");
    }

    /** Returns whether every semantic check passed; the G2 verdict is metadata, not a check. */
    public boolean allPassed() {
        return shared().allPassed();
    }

    /** Renders checks, metadata and the results, then the run configuration and environment. */
    public String toJson() {
        return shared().toJson(Map.of("h2", resultsJson));
    }

    /** Renders a human-readable report with checks and results before the environment. */
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
