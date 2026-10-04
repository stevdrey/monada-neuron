package monada.neuron.evaluation.feedback;

import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * CLI for the cross-cycle feedback-loop evaluation.
 *
 * <p>Options: {@code --quick}, {@code --seed <n>}, {@code --output-dir <dir>}. The exit status is
 * non-zero only when a semantic check fails; timings never affect it.
 */
public final class FeedbackLoopRunner {

    private FeedbackLoopRunner() {
    }

    /** CLI entrypoint. */
    public static void main(String[] args) {
        var quick = false;
        var seed = DeterministicWorkloadGenerator.DEFAULT_SEED;
        var outputDir = Path.of("build/reports/benchmarks");
        try {
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--quick" -> quick = true;
                    case "--seed" -> seed = Long.parseLong(value(args, ++i, "--seed"));
                    case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: [--quick] [--seed <long>] [--output-dir <dir>]");
            System.exit(2);
        }

        var report = run(seed, quick);
        System.out.println(report.toMarkdown());
        try {
            Files.createDirectories(outputDir);
            var json = outputDir.resolve("feedback-loop.json");
            var markdown = outputDir.resolve("feedback-loop.md");
            Files.writeString(json, report.toJson());
            Files.writeString(markdown, report.toMarkdown());
            System.out.println("Feedback-loop reports saved to:\n  " + json.toAbsolutePath()
                    + "\n  " + markdown.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write feedback-loop reports", e);
        }
        if (!report.allPassed()) {
            System.err.println("Semantic feedback-loop checks failed.");
            System.exit(1);
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    /** Runs the evaluation and assembles the report. */
    public static FeedbackLoopReport run(long seed, boolean quick) {
        var feedbackLoop = new FeedbackLoopEvaluation(seed, quick);
        var outcome = feedbackLoop.run();
        var evaluation = new EvaluationReport(
                Instant.now(),
                EnvironmentMetadata.current(),
                feedbackLoop.runConfiguration(),
                outcome.results(),
                FeedbackLoopReport.TITLE);
        return new FeedbackLoopReport(evaluation, outcome.checks(), outcome.metadata());
    }
}
