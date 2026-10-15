package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport;

import java.util.List;
import java.util.Map;

/**
 * Report of a routing replay evaluation: semantic verdicts and metadata followed by the measurements. Rendering is
 * shared with the other checked evaluations through {@link CheckedEvaluationReport}.
 *
 * @param title the report title: {@link #TITLE} for the synthetic replay, {@link #STORE_TITLE} for the Store arm
 * @param evaluation measurements and environment
 * @param checks semantic verdicts
 * @param metadata fixture, digest and summary metadata
 */
public record RoutingReplayReport(
        String title,
        EvaluationReport evaluation,
        List<EvaluationCheck> checks,
        Map<String, String> metadata) {

    public static final String TITLE = "Monada Neuron Forge Routing Replay Report";

    public static final String STORE_TITLE = "Monada Neuron Forge Routing Store Replay Report";

    private static final String METADATA_HEADING = "Routing Replay Metadata";

    /** Snapshots checks and metadata (metadata sorted by key). */
    public RoutingReplayReport {
        var shared = new CheckedEvaluationReport(title, METADATA_HEADING, evaluation, checks, metadata);
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
        return new CheckedEvaluationReport(title, METADATA_HEADING, evaluation, checks, metadata);
    }
}
