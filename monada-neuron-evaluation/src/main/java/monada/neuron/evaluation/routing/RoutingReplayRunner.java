package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * CLI for the Forge routing temporal replay evaluation.
 *
 * <p>Options: {@code --quick}, {@code --output-dir <dir>}, {@code --store-arm}. Exit status: 0 when every semantic
 * check passes, 1 when one fails, 2 for invalid arguments, 3 when a requested prerequisite is unavailable and 4 when
 * the harness itself failed (an unexpected exception, or the report could not be written), so a crash is never read
 * as a routing-quality failure. Timings never affect it.
 *
 * <p>The real Resonance Store arm lives in the optional {@code integration} source set and runs through
 * {@code runForgeRoutingStoreReplay}; this store-free entry point cannot run it, so {@code --store-arm} reports it as
 * unavailable and fails instead of silently passing.
 */
public final class RoutingReplayRunner {

    /** Exit status when a semantic check failed. */
    static final int CHECK_FAILED = 1;
    /** Exit status for invalid arguments. */
    static final int INVALID_ARGUMENTS = 2;
    /** Exit status when a requested prerequisite is unavailable. */
    static final int UNAVAILABLE = 3;
    /** Exit status when the harness failed: an unexpected exception, or the report could not be written. */
    static final int HARNESS_FAILURE = 4;

    private RoutingReplayRunner() {
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
        var storeArm = false;
        var outputDir = Path.of("build/reports/benchmarks");
        try {
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--quick" -> quick = true;
                    case "--store-arm" -> storeArm = true;
                    case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: [--quick] [--output-dir <dir>] [--store-arm]");
            return INVALID_ARGUMENTS;
        }
        if (storeArm) {
            System.err.println(storeArmUnavailable());
            return UNAVAILABLE;
        }
        boolean quickMode = quick;
        return report(() -> run(quickMode), outputDir, "forge-routing-replay",
                "Semantic routing-replay checks failed.");
    }

    /**
     * Runs an evaluation, prints its report and writes it; returns 0 when every check passed, {@link #CHECK_FAILED}
     * when one failed and {@link #HARNESS_FAILURE} when running or writing threw.
     */
    static int report(Supplier<RoutingReplayReport> evaluation, Path outputDir, String name, String failureMessage) {
        RoutingReplayReport report;
        try {
            report = evaluation.get();
            System.out.println(report.toMarkdown());
            write(report, outputDir, name);
        } catch (RuntimeException failure) {
            System.err.println("The routing-replay harness failed; no verdict was reached: " + failure);
            return HARNESS_FAILURE;
        }
        if (!report.allPassed()) {
            System.err.println(failureMessage);
            return CHECK_FAILED;
        }
        return 0;
    }

    /** Why the Store arm cannot run from this entry point, and how to run it. */
    static String storeArmUnavailable() {
        return "UNAVAILABLE: the Resonance Store replay arm is not part of this store-free runner. It runs through"
                + " ./gradlew :monada-neuron-evaluation:runForgeRoutingStoreReplay, which is registered only when the"
                + " sibling checkout ../monada-resonance-store is present (ADR 0018). Nothing was evaluated.";
    }

    /** Runs the evaluation and assembles the report. */
    public static RoutingReplayReport run(boolean quick) {
        var replay = new RoutingReplayEvaluation(quick);
        var outcome = replay.run();
        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(), replay.runConfiguration(),
                outcome.results(), RoutingReplayReport.TITLE);
        return new RoutingReplayReport(RoutingReplayReport.TITLE, evaluation,
                List.<EvaluationCheck>copyOf(outcome.checks()), outcome.metadata());
    }

    /** Writes {@code <name>.json} and {@code <name>.md} into {@code outputDir}. */
    static void write(RoutingReplayReport report, Path outputDir, String name) {
        try {
            Files.createDirectories(outputDir);
            var json = outputDir.resolve(name + ".json");
            var markdown = outputDir.resolve(name + ".md");
            Files.writeString(json, report.toJson());
            Files.writeString(markdown, report.toMarkdown());
            System.out.println("Routing-replay reports saved to:\n  " + json.toAbsolutePath()
                    + "\n  " + markdown.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write routing-replay reports", e);
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}
