package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.HostProbes;
import monada.neuron.evaluation.wave.NextConceptG5Evaluation.Arm;
import monada.neuron.evaluation.wave.NextConceptG5Evaluation.FeedbackRun;
import monada.neuron.evaluation.wave.NextConceptG5Evaluation.Outcome;
import monada.neuron.evaluation.wave.NextConceptG5Evaluation.Protocol;
import monada.neuron.evaluation.wave.NextConceptG5Evaluation.RepeatResult;
import monada.neuron.reasoning.concept.ConceptFeedbackRejection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static monada.neuron.evaluation.wave.NextConceptG3Evaluation.format;

/**
 * CLI for the G5 evaluation of issue #129 (hypothesis H5, control E), protocol
 * {@code docs/benchmarks/next-concept-g5.md}.
 *
 * <p>Options: {@code --quick} (first repeat, 8 sequences, 200 resamples) and {@code --output-dir <dir>}. Exit status:
 * 0 when every semantic check passes, whatever the H5 verdict; 1 when a check fails; 2 for invalid arguments; 4 when
 * the harness itself failed, so a crash is never read as a verdict.
 */
public final class NextConceptG5Runner {

    /** Exit status when a semantic check failed. */
    static final int CHECK_FAILED = 1;
    /** Exit status for invalid arguments. */
    static final int INVALID_ARGUMENTS = 2;
    /** Exit status when the harness failed: an unexpected exception, or the report could not be written. */
    static final int HARNESS_FAILURE = 4;

    private static final String NAME = "next-concept-g5";

    NextConceptG5Runner() {
    }

    /** CLI entrypoint; exits with {@link #execute}'s status of a new runner when it is not 0. */
    public static void main(String[] args) {
        int status = new NextConceptG5Runner().execute(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    /** Parses the arguments, runs, prints and writes the report; returns the exit status. */
    int execute(String[] args) {
        var quick = false;
        var outputDir = Path.of("build/reports/benchmarks");
        try {
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--quick" -> quick = true;
                    case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: [--quick] [--output-dir <dir>]");
            return INVALID_ARGUMENTS;
        }
        var protocol = quick ? NextConceptG5Evaluation.REGISTERED.quick() : NextConceptG5Evaluation.REGISTERED;
        boolean quickMode = quick;
        return report(() -> run(protocol, quickMode), outputDir);
    }

    /**
     * Runs an evaluation, prints its report and writes it; returns 0 when every check passed, {@link #CHECK_FAILED}
     * when one failed and {@link #HARNESS_FAILURE} when running or writing threw.
     */
    int report(Supplier<NextConceptG5Report> evaluation, Path outputDir) {
        NextConceptG5Report report;
        try {
            report = evaluation.get();
            System.out.println(report.toMarkdown());
            write(report, outputDir);
        } catch (RuntimeException | Error failure) {
            // An Error (out of memory, stack overflow) is a harness failure too, never a check verdict.
            System.err.println("The G5 harness failed; no verdict was reached: " + failure);
            return HARNESS_FAILURE;
        }
        if (!report.allPassed()) {
            System.err.println("G5 semantic checks failed; the H5 verdict cannot be trusted.");
            return CHECK_FAILED;
        }
        return 0;
    }

    /** Runs the evaluation under {@code protocol} and assembles the report. */
    NextConceptG5Report run(Protocol protocol, boolean quick) {
        var g5 = new NextConceptG5Evaluation(protocol, quick);
        Outcome outcome = g5.run();
        var probes = new HostProbes();
        var metadata = new LinkedHashMap<>(outcome.metadata());
        metadata.put("env.cpuModel", probes.cpuModel(Path.of("/proc/cpuinfo")));
        metadata.put("neuron.commit", probes.gitCommit(Path.of("").toAbsolutePath()));
        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(), g5.runConfiguration(),
                List.of(), NextConceptG5Report.TITLE);
        return new NextConceptG5Report(evaluation, outcome.checks(), metadata, resultsJson(protocol, outcome),
                resultsMarkdown(protocol, outcome));
    }

    String resultsMarkdown(Protocol protocol, Outcome outcome) {
        var sb = new StringBuilder("## G5 Results\n\n");
        sb.append("H5 verdict: **").append(outcome.holds() ? "HOLDS" : "NEGATIVE").append("** (margin ")
                .append(format(protocol.h5Margin())).append(", minimum E leaf coverage ")
                .append(format(protocol.h5MinCoverage())).append(").\n\n");
        for (RepeatResult repeat : outcome.repeats()) {
            sb.append("### Repeat ").append(repeat.number()).append(" (feedback seed ")
                    .append(NextConceptG5Evaluation.hex(repeat.feedbackSeed())).append(", test seed ")
                    .append(NextConceptG5Evaluation.hex(repeat.testSeed())).append("; gate ")
                    .append(repeat.gatePassed() ? "pass" : "fail").append(")\n\n");
            sb.append("| Gate quantity | Point | 95% interval | Margin |\n| :--- | ---: | :--- | ---: |\n");
            sb.append("| H5: rate(E) - rate(C) | ").append(format(repeat.rate()[Arm.E] - repeat.rate()[Arm.C]))
                    .append(" | ").append(format(repeat.h5())).append(" | > ").append(format(protocol.h5Margin()))
                    .append(" |\n");
            sb.append("| E leaf coverage | ").append(format(repeat.coverage()[Arm.E])).append(" | ")
                    .append(format(repeat.eCoverageInterval())).append(" | >= ")
                    .append(format(protocol.h5MinCoverage())).append(" |\n");
            sb.append("| (control) rate(E-shuffled) - rate(C) | ")
                    .append(format(repeat.rate()[Arm.SHUFFLED] - repeat.rate()[Arm.C])).append(" | ")
                    .append(format(repeat.shuffledDelta())).append(" | |\n");
            sb.append("| (control) rate(E) - rate(C-replay) | ")
                    .append(format(repeat.rate()[Arm.E] - repeat.rate()[Arm.REPLAY])).append(" | ")
                    .append(format(repeat.replayDelta())).append(" | |\n\n");
            sb.append("| Arm | Covered-correct rate | Leaf coverage | Covered accuracy |\n")
                    .append("| :--- | ---: | ---: | ---: |\n");
            for (int arm = 0; arm < repeat.rate().length; arm++) {
                sb.append("| ").append(Arm.NAMES.get(arm)).append(" | ").append(format(repeat.rate()[arm]))
                        .append(" | ").append(format(repeat.coverage()[arm])).append(" | ")
                        .append(format(repeat.coveredAccuracy()[arm])).append(" |\n");
            }
            sb.append('\n');
            feedback(sb, "E", repeat.e());
            feedback(sb, "E-shuffled", repeat.shuffled());
            feedback(sb, "E-unverified", repeat.unverified());
            sb.append('\n');
        }
        return sb.toString();
    }

    private void feedback(StringBuilder sb, String name, FeedbackRun run) {
        sb.append("- ").append(name).append(": ").append(run.outcomes()).append(" outcomes over ")
                .append(run.steps()).append(" steps, ").append(run.applied()).append(" applied (")
                .append(run.positive()).append(" positive, ").append(run.applied() - run.positive())
                .append(" negative), final revision ").append(run.last().revision()).append("; rejections ")
                .append(rejections(run)).append("; ").append(perUpdate(run)).append(" bytes copied per update (max ")
                .append(run.maxCopiedBytes()).append("), retained ").append(run.last().retainedBytes())
                .append(" bytes.\n");
    }

    private String rejections(FeedbackRun run) {
        var parts = new StringBuilder();
        for (ConceptFeedbackRejection reason : ConceptFeedbackRejection.values()) {
            int count = run.rejections()[reason.ordinal()];
            if (count > 0) {
                parts.append(parts.isEmpty() ? "" : ", ").append(reason).append(' ').append(count);
            }
        }
        return parts.isEmpty() ? "none" : parts.toString();
    }

    private String perUpdate(FeedbackRun run) {
        return run.applied() == 0 ? "0" : String.format(Locale.ROOT, "%.0f",
                run.copiedBytes() / (double) run.applied());
    }

    String resultsJson(Protocol protocol, Outcome outcome) {
        var sb = new StringBuilder("{ \"h5Holds\": ").append(outcome.holds())
                .append(", \"margins\": { \"h5\": ").append(protocol.h5Margin()).append(", \"h5MinCoverage\": ")
                .append(protocol.h5MinCoverage()).append(" }, \"snrLevelsDb\": ").append(protocol.snrLevels())
                .append(", \"temperature\": ").append(outcome.selection().model().temperature())
                .append(", \"thresholds\": \"").append(outcome.selection().model().thresholds())
                .append("\", \"arms\": [");
        for (int arm = 0; arm < Arm.NAMES.size(); arm++) {
            sb.append(arm == 0 ? "" : ", ").append('"').append(Arm.NAMES.get(arm)).append('"');
        }
        sb.append("], \"repeats\": [");
        for (int r = 0; r < outcome.repeats().size(); r++) {
            RepeatResult repeat = outcome.repeats().get(r);
            sb.append(r == 0 ? "\n" : ",\n").append("    { \"repeat\": ").append(repeat.number())
                    .append(", \"feedbackSeed\": ").append(repeat.feedbackSeed())
                    .append(", \"testSeed\": ").append(repeat.testSeed())
                    .append(", \"gate\": ").append(repeat.gatePassed())
                    .append(", \"h5\": ").append(interval(repeat.h5()))
                    .append(", \"eCoverage\": ").append(interval(repeat.eCoverageInterval()))
                    .append(", \"shuffledDelta\": ").append(interval(repeat.shuffledDelta()))
                    .append(", \"replayDelta\": ").append(interval(repeat.replayDelta()))
                    .append(", \"rate\": ").append(Arrays.toString(repeat.rate()))
                    .append(", \"coverage\": ").append(Arrays.toString(repeat.coverage()))
                    .append(", \"coveredAccuracy\": ").append(Arrays.toString(repeat.coveredAccuracy()))
                    .append(", \"feedbackApplied\": ").append(repeat.e().applied())
                    .append(", \"feedbackOutcomes\": ").append(repeat.e().outcomes())
                    .append(", \"rejections\": ").append(Arrays.toString(repeat.e().rejections())).append(" }");
        }
        return sb.append("\n  ] }").toString();
    }

    private String interval(RankStatistics.Interval interval) {
        return "{ \"lower\": " + interval.lower() + ", \"upper\": " + interval.upper() + " }";
    }

    /** Writes {@code next-concept-g5.json} and {@code .md} into {@code outputDir}. */
    void write(NextConceptG5Report report, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            var json = outputDir.resolve(NAME + ".json");
            var markdown = outputDir.resolve(NAME + ".md");
            Files.writeString(json, report.toJson());
            Files.writeString(markdown, report.toMarkdown());
            System.out.println("G5 reports saved to:\n  " + json.toAbsolutePath() + "\n  "
                    + markdown.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write G5 reports", e);
        }
    }

    private String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}
