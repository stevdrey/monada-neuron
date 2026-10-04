package monada.neuron.evaluation.feedback;

import monada.neuron.evaluation.feedback.FeedbackLoopEvaluation.Check;
import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport;

import java.util.List;
import java.util.Map;

/**
 * Report of the feedback-loop evaluation: semantic verdicts and metadata followed by the measurements.
 * Rendering is shared with the other checked evaluations through {@link CheckedEvaluationReport}.
 */
public record FeedbackLoopReport(
        EvaluationReport evaluation,
        List<Check> checks,
        Map<String, String> metadata) {

    public static final String TITLE = "Monada Neuron Cross-Cycle Feedback Loop Report";

    private static final String METADATA_HEADING = "Feedback Loop Metadata";

    /** Snapshots checks and metadata (metadata sorted by key). */
    public FeedbackLoopReport {
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
