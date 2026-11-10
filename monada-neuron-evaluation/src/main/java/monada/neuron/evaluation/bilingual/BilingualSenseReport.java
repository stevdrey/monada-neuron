package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Report of a {@code bilingual-sense/2} run (issue #185): checks, metadata with the label status, per-direction
 * verdicts and results, then the cost measurements, run configuration and environment. Rendering is shared through
 * {@link CheckedEvaluationReport}.
 *
 * @param evaluation run configuration, environment and cost measurements
 * @param checks semantic checks
 * @param metadata protocol, provenance, label status and run metadata
 * @param resultsJson results as a JSON value
 * @param resultsMarkdown results as Markdown tables
 */
record BilingualSenseReport(EvaluationReport evaluation, List<EvaluationCheck> checks, Map<String, String> metadata,
                            String resultsJson, String resultsMarkdown) {

    /** Report title of the synthetic self-test. */
    static final String SYNTHETIC_TITLE =
            "Monada Neuron Bilingual Sense Harness Self-Test (synthetic data, not a result)";

    private static final String METADATA_HEADING = "Protocol, Provenance and Label Status";

    /** Snapshots checks and metadata. */
    BilingualSenseReport {
        checks = List.copyOf(checks);
        metadata = shared(evaluation, checks, metadata).metadata();
        Objects.requireNonNull(resultsJson, "resultsJson must not be null");
        Objects.requireNonNull(resultsMarkdown, "resultsMarkdown must not be null");
    }

    /** Returns whether every check passed; verdicts are results, not checks. */
    boolean allPassed() {
        return shared(evaluation, checks, metadata).allPassed();
    }

    /** Renders checks, metadata and results, then the measurements, run configuration and environment. */
    String toJson() {
        return shared(evaluation, checks, metadata).toJson(Map.of("bilingualSense", resultsJson));
    }

    /** Renders a human-readable report with checks and results before the measurements and environment. */
    String toMarkdown() {
        var body = shared(evaluation, checks, metadata).toMarkdown();
        var marker = "## Run Configuration";
        var at = body.indexOf(marker);
        return at < 0 ? body + resultsMarkdown : body.substring(0, at) + resultsMarkdown + "\n" + body.substring(at);
    }

    private static CheckedEvaluationReport shared(EvaluationReport evaluation, List<EvaluationCheck> checks,
                                                  Map<String, String> metadata) {
        return new CheckedEvaluationReport(evaluation.title(), METADATA_HEADING, evaluation, checks, metadata);
    }
}
