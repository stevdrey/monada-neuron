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

/**
 * CLI for the Forge routing temporal replay evaluation.
 *
 * <p>Options: {@code --quick}, {@code --output-dir <dir>}, {@code --store-arm}. Exit status: 0 when every semantic
 * check passes, 1 when one fails, 2 for invalid arguments and 3 when a requested prerequisite is unavailable. Timings
 * never affect it.
 *
 * <p>The real Resonance Store arm lives in the optional {@code integration} source set and runs through
 * {@code runForgeRoutingStoreReplay}; this store-free entry point cannot run it, so {@code --store-arm} reports it as
 * unavailable and fails instead of silently passing.
 */
public final class RoutingReplayRunner {

    /** Exit status when a requested prerequisite is unavailable. */
    static final int UNAVAILABLE = 3;

    private RoutingReplayRunner() {
    }

    /** CLI entrypoint. */
    public static void main(String[] args) {
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
            System.exit(2);
        }
        if (storeArm) {
            System.err.println(storeArmUnavailable());
            System.exit(UNAVAILABLE);
        }

        var report = run(quick);
        System.out.println(report.toMarkdown());
        write(report, outputDir, "forge-routing-replay");
        if (!report.allPassed()) {
            System.err.println("Semantic routing-replay checks failed.");
            System.exit(1);
        }
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
