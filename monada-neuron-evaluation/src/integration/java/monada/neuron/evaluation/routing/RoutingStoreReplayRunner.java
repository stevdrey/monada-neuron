package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.integration.StoreMetadata;
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
 * <p>Options: {@code --quick}, {@code --output-dir <dir>}. Exit status, as for {@link RoutingReplayRunner}: 0 when
 * every check passes, 1 when one fails (including a setup failure: an unavailable Store is reported, never silently
 * passed), 2 for invalid arguments and 4 when the harness itself failed.
 */
public final class RoutingStoreReplayRunner {

    private static final String NEURON_VERSION_PROPERTY = "monada.neuron.version";
    private static final String STORE_DIRECTORY_PROPERTY = "monada.resonance.store.dir";

    private RoutingStoreReplayRunner() {
    }

    /** CLI entrypoint; exits with {@link #execute}'s status when it is not 0. */
    public static void main(String[] args) {
        int status = execute(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    /** Parses the arguments, runs, prints and writes the report; returns the exit status. */
    static int execute(String[] args) {
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
            return RoutingReplayRunner.INVALID_ARGUMENTS;
        }
        boolean quickMode = quick;
        return RoutingReplayRunner.report(() -> run(quickMode), outputDir, "forge-routing-store-replay",
                "Store routing-replay checks failed.");
    }

    /** Runs the evaluation and assembles the report. */
    public static RoutingReplayReport run(boolean quick) {
        var store = new StoreReplayEvaluation(quick);
        var outcome = store.run();
        var metadata = new TreeMap<>(outcome.metadata());
        metadata.put("neuron.version", System.getProperty(NEURON_VERSION_PROPERTY, "unknown"));
        // the export digest depends on both checkouts' code, so each report names the revisions that produced it
        metadata.put("neuron.commit", StoreMetadata.gitCommit(Path.of(".").toAbsolutePath().normalize()));
        String storeDirectory = System.getProperty(STORE_DIRECTORY_PROPERTY);
        metadata.put("store.directory", storeDirectory == null ? "unknown" : storeDirectory);
        metadata.put("store.commit", StoreMetadata.gitCommit(storeDirectory == null ? null : Path.of(storeDirectory)));
        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(), store.runConfiguration(),
                outcome.results(), RoutingReplayReport.STORE_TITLE);
        return new RoutingReplayReport(RoutingReplayReport.STORE_TITLE, evaluation,
                List.<EvaluationCheck>copyOf(outcome.checks()), metadata);
    }
}
