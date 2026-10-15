package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;

/**
 * CLI for the routing replay over a real Resonance Store export (optional integration arm).
 *
 * <p>Options: {@code --quick}, {@code --output-dir <dir>}. The exit status is non-zero when any check fails,
 * including a setup failure: an unavailable Store is reported, never silently passed.
 */
public final class RoutingStoreReplayRunner {

    private static final String NEURON_VERSION_PROPERTY = "monada.neuron.version";
    private static final String STORE_DIRECTORY_PROPERTY = "monada.resonance.store.dir";

    private RoutingStoreReplayRunner() {
    }

    /** CLI entrypoint. */
    public static void main(String[] args) {
        var quick = false;
        var outputDir = Path.of("build/reports/benchmarks");
        try {
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--quick" -> quick = true;
                    case "--output-dir" -> {
                        if (++i >= args.length) {
                            throw new IllegalArgumentException("--output-dir requires a value");
                        }
                        outputDir = Path.of(args[i]);
                    }
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: [--quick] [--output-dir <dir>]");
            System.exit(2);
        }
        var report = run(quick);
        System.out.println(report.toMarkdown());
        RoutingReplayRunner.write(report, outputDir, "forge-routing-store-replay");
        if (!report.allPassed()) {
            System.err.println("Store routing-replay checks failed.");
            System.exit(1);
        }
    }

    /** Runs the evaluation and assembles the report. */
    public static RoutingReplayReport run(boolean quick) {
        var store = new StoreReplayEvaluation(quick);
        var outcome = store.run();
        var metadata = new TreeMap<>(outcome.metadata());
        metadata.put("neuron.version", System.getProperty(NEURON_VERSION_PROPERTY, "unknown"));
        metadata.put("store.directory", System.getProperty(STORE_DIRECTORY_PROPERTY, "unknown"));
        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(), store.runConfiguration(),
                outcome.results(), RoutingReplayReport.STORE_TITLE);
        return new RoutingReplayReport(RoutingReplayReport.STORE_TITLE, evaluation,
                List.<EvaluationCheck>copyOf(outcome.checks()), metadata);
    }
}
