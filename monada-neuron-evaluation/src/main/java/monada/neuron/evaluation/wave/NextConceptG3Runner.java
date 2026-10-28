package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.HostProbes;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.Outcome;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.Protocol;
import monada.neuron.evaluation.wave.NextConceptG3Evaluation.RepeatResult;
import monada.neuron.evaluation.wave.SelectiveMetrics.Reliability;

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
 * CLI for the G3 evaluation of issue #127 (hypotheses H3, H4 and H6), protocol
 * {@code docs/benchmarks/next-concept-g3.md}.
 *
 * <p>Options: {@code --quick} (first repeat, 8 sequences, 200 resamples) and {@code --output-dir <dir>}. Exit status:
 * 0 when every semantic check passes, whatever the G3 verdict; 1 when a check fails; 2 for invalid arguments; 4 when
 * the harness itself failed, so a crash is never read as a verdict.
 */
public final class NextConceptG3Runner {

    /** Exit status when a semantic check failed. */
    static final int CHECK_FAILED = 1;
    /** Exit status for invalid arguments. */
    static final int INVALID_ARGUMENTS = 2;
    /** Exit status when the harness failed: an unexpected exception, or the report could not be written. */
    static final int HARNESS_FAILURE = 4;

    private static final String NAME = "next-concept-g3";
    private static final List<String> ARMS = List.of("C (covered)", "A on C-covered (H3)", "A-oracle (informational)",
            "B", "C forced top-1", "C-flat (covered)", "A (all windows)");

    NextConceptG3Runner() {
    }

    /** CLI entrypoint; exits with {@link #execute}'s status of a new runner when it is not 0. */
    public static void main(String[] args) {
        int status = new NextConceptG3Runner().execute(args);
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
        var protocol = quick ? NextConceptG3Evaluation.REGISTERED.quick() : NextConceptG3Evaluation.REGISTERED;
        boolean quickMode = quick;
        return report(() -> run(protocol, quickMode), outputDir);
    }

    /**
     * Runs an evaluation, prints its report and writes it; returns 0 when every check passed, {@link #CHECK_FAILED}
     * when one failed and {@link #HARNESS_FAILURE} when running or writing threw.
     */
    int report(Supplier<NextConceptG3Report> evaluation, Path outputDir) {
        NextConceptG3Report report;
        try {
            report = evaluation.get();
            System.out.println(report.toMarkdown());
            write(report, outputDir);
        } catch (RuntimeException | Error failure) {
            // An Error (out of memory, stack overflow) is a harness failure too, never a check verdict.
            System.err.println("The G3 harness failed; no verdict was reached: " + failure);
            return HARNESS_FAILURE;
        }
        if (!report.allPassed()) {
            System.err.println("G3 semantic checks failed; the G3 verdict cannot be trusted.");
            return CHECK_FAILED;
        }
        return 0;
    }

    /** Runs the evaluation under {@code protocol} and assembles the report. */
    NextConceptG3Report run(Protocol protocol, boolean quick) {
        var g3 = new NextConceptG3Evaluation(protocol, quick);
        Outcome outcome = g3.run();
        var probes = new HostProbes();
        var metadata = new LinkedHashMap<>(outcome.metadata());
        metadata.put("env.cpuModel", probes.cpuModel(Path.of("/proc/cpuinfo")));
        metadata.put("neuron.commit", probes.gitCommit(Path.of("").toAbsolutePath()));
        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(), g3.runConfiguration(),
                List.of(), NextConceptG3Report.TITLE);
        return new NextConceptG3Report(evaluation, outcome.checks(), metadata, resultsJson(protocol, outcome),
                resultsMarkdown(protocol, outcome));
    }

    String resultsMarkdown(Protocol protocol, Outcome outcome) {
        var sb = new StringBuilder("## G3 Results\n\n");
        sb.append("G3 verdict: **").append(outcome.go() ? "GO" : "NO-GO").append("**. H4 (informational): ")
                .append(outcome.h4() ? "holds" : "refuted").append(". N11 leaf non-coverage: ")
                .append(format(outcome.noiseUncovered())).append(" of ").append(outcome.noiseWindows())
                .append(" noise windows.\n\n");
        for (RepeatResult repeat : outcome.repeats()) {
            sb.append("### Repeat ").append(repeat.number()).append(" (test seed 0x")
                    .append(Long.toHexString(repeat.seed()).toUpperCase(Locale.ROOT)).append("; gate ")
                    .append(repeat.gatePassed() ? "pass" : "fail").append(")\n\n");
            sb.append("| Gate quantity | Point | 95% interval | Margin |\n| :--- | ---: | :--- | ---: |\n");
            row(sb, "H3: acc(C) - acc(A), C-covered", repeat.cAccuracy() - repeat.aAccuracy(), repeat.h3(),
                    "> " + format(protocol.h3Margin()));
            sb.append("| C leaf coverage | ").append(format(repeat.coverage())).append(" | ")
                    .append(format(repeat.coverageInterval())).append(" | >= ")
                    .append(format(protocol.h3MinCoverage())).append(" |\n");
            row(sb, "H6: acc(C covered) - acc(C forced)", repeat.cAccuracy() - repeat.forcedAccuracy(),
                    repeat.h6(), "> " + format(protocol.h6Margin()));
            sb.append("| H6: N11 vs 20 dB non-coverage | ").append(format(outcome.noiseUncovered())).append(" vs ")
                    .append(format(repeat.cleanUncovered())).append(" | | N11 higher |\n");
            row(sb, "H4 (info): acc(C) - acc(C-flat)", repeat.cAccuracy() - repeat.flatAccuracy(), repeat.h4(),
                    "> " + format(protocol.h4Margin()));
            sb.append('\n');

            sb.append("| Next-leaf accuracy |");
            protocol.snrLevels().forEach(snr -> sb.append(' ').append(decibels(snr)).append(" dB |"));
            sb.append(" pooled |\n| :--- |");
            protocol.snrLevels().forEach(snr -> sb.append(" ---: |"));
            sb.append(" ---: |\n");
            for (int arm = 0; arm < ARMS.size(); arm++) {
                values(sb, ARMS.get(arm), repeat.armAccuracy()[arm]);
            }
            values(sb, "C leaf coverage", repeat.coverageByLevel());
            values(sb, "C depth-0 coverage", repeat.rootCoverageByLevel());
            values(sb, "C depth-0 accuracy (covered)", repeat.rootAccuracyByLevel());
            values(sb, "C forced top-2 leaf accuracy", repeat.top2ByLevel());
            sb.append('\n');

            int windows = repeat.splits().size() * repeat.splits().getFirst().patterns().length;
            sb.append("- Abstentions ").append(repeat.abstentions()).append(", coarse-only ")
                    .append(repeat.coarseOnly()).append(" of ").append(windows)
                    .append(" windows; abstention precision ").append(format(repeat.abstentionPrecision()))
                    .append(", recall ").append(format(repeat.abstentionRecall()))
                    .append(" (against C's forced top-1 errors).\n");
            sb.append("- C-flat leaf coverage ").append(format(repeat.flatCoverage())).append(".\n");
            sb.append("- Work per window: ").append(perWindow(repeat.comparisons(), windows))
                    .append(" comparisons, ").append(perWindow(repeat.candidates(), windows))
                    .append(" candidates scored, ").append(perWindow(repeat.levelsVisited(), windows))
                    .append(" levels visited.\n");
            sb.append("- ECE: forced top-1 leaf ").append(format(repeat.forcedReliability().ece()))
                    .append(", covered leaf ").append(format(repeat.coveredReliability().ece()))
                    .append(", depth 0 ").append(format(repeat.rootReliability().ece())).append(".\n");
            sb.append("- Risk-coverage (forced top-1 accuracy at 10%..100% coverage): ")
                    .append(formatted(repeat.riskCoverage())).append(".\n\n");
            reliability(sb, "Reliability, forced top-1 leaf", repeat.forcedReliability());
            reliability(sb, "Reliability, covered leaf", repeat.coveredReliability());
        }
        return sb.toString();
    }

    private void row(StringBuilder sb, String name, double point, RankStatistics.Interval interval, String margin) {
        sb.append("| ").append(name).append(" | ").append(format(point)).append(" | ").append(format(interval))
                .append(" | ").append(margin).append(" |\n");
    }

    private void values(StringBuilder sb, String name, double[] values) {
        sb.append("| ").append(name).append(" |");
        for (double value : values) {
            sb.append(' ').append(format(value)).append(" |");
        }
        sb.append('\n');
    }

    private void reliability(StringBuilder sb, String title, Reliability reliability) {
        sb.append("| ").append(title).append(" | n | mean score | accuracy |\n| :--- | ---: | ---: | ---: |\n");
        for (int b = 0; b < SelectiveMetrics.BINS; b++) {
            if (reliability.counts()[b] == 0) {
                continue;
            }
            sb.append("| [").append(format(b / 10.0)).append(", ").append(format((b + 1) / 10.0)).append(") | ")
                    .append(reliability.counts()[b]).append(" | ").append(format(reliability.meanScores()[b]))
                    .append(" | ").append(format(reliability.accuracies()[b])).append(" |\n");
        }
        sb.append('\n');
    }

    String resultsJson(Protocol protocol, Outcome outcome) {
        var sb = new StringBuilder("{ \"go\": ").append(outcome.go()).append(", \"h4Holds\": ").append(outcome.h4())
                .append(", \"margins\": { \"h3\": ").append(protocol.h3Margin()).append(", \"h3MinCoverage\": ")
                .append(protocol.h3MinCoverage()).append(", \"h6\": ").append(protocol.h6Margin())
                .append(", \"h4\": ").append(protocol.h4Margin()).append(" }, \"snrLevelsDb\": ")
                .append(protocol.snrLevels()).append(", \"temperature\": ")
                .append(outcome.selection().model().temperature()).append(", \"thresholds\": \"")
                .append(outcome.selection().model().thresholds()).append("\", \"n11Uncovered\": ")
                .append(outcome.noiseUncovered()).append(", \"repeats\": [");
        for (int r = 0; r < outcome.repeats().size(); r++) {
            RepeatResult repeat = outcome.repeats().get(r);
            sb.append(r == 0 ? "\n" : ",\n").append("    { \"repeat\": ").append(repeat.number())
                    .append(", \"testSeed\": ").append(repeat.seed())
                    .append(", \"gate\": ").append(repeat.gatePassed())
                    .append(", \"coverage\": ").append(repeat.coverage())
                    .append(", \"cAccuracy\": ").append(repeat.cAccuracy())
                    .append(", \"aAccuracyOnCovered\": ").append(repeat.aAccuracy())
                    .append(", \"forcedAccuracy\": ").append(repeat.forcedAccuracy())
                    .append(", \"flatAccuracy\": ").append(repeat.flatAccuracy())
                    .append(", \"flatCoverage\": ").append(repeat.flatCoverage())
                    .append(", \"h3\": ").append(interval(repeat.h3()))
                    .append(", \"h6\": ").append(interval(repeat.h6()))
                    .append(", \"h4\": ").append(interval(repeat.h4()))
                    .append(", \"cleanUncovered\": ").append(repeat.cleanUncovered())
                    .append(", \"eceForced\": ").append(repeat.forcedReliability().ece())
                    .append(", \"eceCovered\": ").append(repeat.coveredReliability().ece())
                    .append(", \"riskCoverage\": ").append(Arrays.toString(repeat.riskCoverage()))
                    .append(", \"armAccuracy\": ").append(Arrays.deepToString(repeat.armAccuracy())).append(" }");
        }
        return sb.append("\n  ] }").toString();
    }

    private String interval(RankStatistics.Interval interval) {
        return "{ \"lower\": " + interval.lower() + ", \"upper\": " + interval.upper() + " }";
    }

    private String perWindow(long total, int windows) {
        return String.format(Locale.ROOT, "%.2f", total / (double) windows);
    }

    private String formatted(double[] values) {
        var parts = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            parts[i] = format(values[i]);
        }
        return String.join(", ", parts);
    }

    /** Writes {@code next-concept-g3.json} and {@code .md} into {@code outputDir}. */
    void write(NextConceptG3Report report, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            var json = outputDir.resolve(NAME + ".json");
            var markdown = outputDir.resolve(NAME + ".md");
            Files.writeString(json, report.toJson());
            Files.writeString(markdown, report.toMarkdown());
            System.out.println("G3 reports saved to:\n  " + json.toAbsolutePath() + "\n  "
                    + markdown.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write G3 reports", e);
        }
    }

    /** Renders an SNR level without trailing zeros, such as {@code 20} or {@code 2.5}. */
    private String decibels(double snr) {
        return snr == StrictMath.rint(snr) ? Long.toString((long) snr) : Double.toString(snr);
    }

    private String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}
