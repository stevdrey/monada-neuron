package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * CLI for the Forge host scenario evaluation (issue #103).
 *
 * <p>Option: {@code --output-dir <dir>} (default {@code build/reports/benchmarks}). Exit status: 0 when every check
 * passes, 1 when one fails, 2 for invalid arguments and 4 when the harness itself failed, so a crash is never read as a
 * contract failure. There is no optional prerequisite, so status 3 is never used.
 */
public final class HostScenarioRunner {

    /** Report title. */
    static final String TITLE = "Forge Host Scenario Evaluation";

    /** Base name of the written report files. */
    static final String REPORT_NAME = "forge-host-scenarios";

    private HostScenarioRunner() {
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
        return execute(args, () -> new HostScenarioEvaluation().run());
    }

    /** {@link #execute(String[])} over a given evaluation, so tests can inject a failing or throwing one. */
    static int execute(String[] args, Supplier<HostScenarioEvaluation.Outcome> evaluation) {
        var outputDir = Path.of("build/reports/benchmarks");
        try {
            for (var i = 0; i < args.length; i++) {
                if (!args[i].equals("--output-dir")) {
                    throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
                if (++i >= args.length) {
                    throw new IllegalArgumentException("--output-dir requires a value");
                }
                outputDir = Path.of(args[i]);
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: [--output-dir <dir>]");
            return RoutingReplayRunner.INVALID_ARGUMENTS;
        }
        return RoutingReplayRunner.report(() -> report(evaluation.get()), outputDir, REPORT_NAME,
                "Host scenario checks failed.");
    }

    /** Assembles the report of one evaluation outcome; nothing is timed, so there are no measurement rows. */
    static RoutingReplayReport report(HostScenarioEvaluation.Outcome outcome) {
        var configuration = new EvaluationReport.RunConfiguration(0L, false, 0, 0,
                "Nothing is timed: each scenario is replayed once per arm with a fresh host script, and the"
                        + " determinism check replays everything a second time from fresh builds; fixtures are"
                        + " enumerated, so the seed is unused");
        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(), configuration, List.of(),
                TITLE);
        return new RoutingReplayReport(TITLE, evaluation, List.<EvaluationCheck>copyOf(outcome.checks()),
                outcome.metadata());
    }
}
