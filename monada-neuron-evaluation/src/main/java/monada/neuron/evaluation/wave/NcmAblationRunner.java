package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.evaluation.metrics.HostProbes;
import monada.neuron.evaluation.wave.LargeWorkloadEvaluation.ArmScore;
import monada.neuron.evaluation.wave.NcmAblationEvaluation.ArmResult;
import monada.neuron.evaluation.wave.NcmAblationEvaluation.Outcome;
import monada.neuron.evaluation.wave.NcmAblationEvaluation.Protocol;
import monada.neuron.evaluation.wave.NcmAblationEvaluation.RepeatResult;
import monada.neuron.evaluation.wave.RankStatistics.Interval;

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
 * CLI for the consolidated NCM ablation and go/no-go report of issue #130, protocol
 * {@code docs/experiments/ncm-ablation-v1.md}.
 *
 * <p>Options: {@code --quick} (the quick G3, G5 and large protocols, two measured iterations) and
 * {@code --output-dir <dir>}. Exit status: 0 when every semantic check passes, whatever the verdict; 1 when a check
 * fails; 2 for invalid arguments; 4 when the harness itself failed, so a crash is never read as a verdict.
 */
public final class NcmAblationRunner {

    /** Exit status when a semantic check failed. */
    static final int CHECK_FAILED = 1;
    /** Exit status for invalid arguments. */
    static final int INVALID_ARGUMENTS = 2;
    /** Exit status when the harness failed: an unexpected exception, or the report could not be written. */
    static final int HARNESS_FAILURE = 4;

    private static final String NAME = "ncm-ablation";

    NcmAblationRunner() {
    }

    /** CLI entrypoint; exits with {@link #execute}'s status of a new runner when it is not 0. */
    public static void main(String[] args) {
        int status = new NcmAblationRunner().execute(args);
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
        var protocol = quick ? NcmAblationEvaluation.REGISTERED.quick() : NcmAblationEvaluation.REGISTERED;
        boolean quickMode = quick;
        return report(() -> run(protocol, quickMode), outputDir);
    }

    /**
     * Runs an evaluation, prints its report and writes it; returns 0 when every check passed, {@link #CHECK_FAILED}
     * when one failed and {@link #HARNESS_FAILURE} when running or writing threw.
     */
    int report(Supplier<NcmAblationReport> evaluation, Path outputDir) {
        NcmAblationReport report;
        try {
            report = evaluation.get();
            System.out.println(report.toMarkdown());
            write(report, outputDir);
        } catch (RuntimeException | Error failure) {
            // An Error (out of memory, stack overflow) is a harness failure too, never a check verdict.
            System.err.println("The NCM ablation harness failed; no verdict was reached: " + failure);
            return HARNESS_FAILURE;
        }
        if (!report.allPassed()) {
            System.err.println("NCM ablation semantic checks failed; the verdict cannot be trusted.");
            return CHECK_FAILED;
        }
        return 0;
    }

    /** Runs the evaluation under {@code protocol} and assembles the report. */
    NcmAblationReport run(Protocol protocol, boolean quick) {
        var ablation = new NcmAblationEvaluation(protocol, quick);
        return reportOf(ablation.run(), protocol, ablation.runConfiguration());
    }

    /** Assembles the report of a finished run, adding the CPU model and the Neuron commit. */
    NcmAblationReport reportOf(Outcome outcome, Protocol protocol, RunConfiguration runConfiguration) {
        var probes = new HostProbes();
        var metadata = new LinkedHashMap<>(outcome.metadata());
        metadata.put("env.cpuModel", probes.cpuModel(Path.of("/proc/cpuinfo")));
        metadata.put("neuron.commit", probes.gitCommit(Path.of("").toAbsolutePath()));
        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(),
                runConfiguration, outcome.resources(), NcmAblationReport.TITLE);
        return new NcmAblationReport(evaluation, outcome.checks(), metadata, resultsJson(outcome),
                resultsMarkdown(protocol, outcome));
    }

    String resultsMarkdown(Protocol protocol, Outcome outcome) {
        var sb = new StringBuilder("## Ablation Results\n\n");
        sb.append("Verdict: **").append(outcome.verdict()).append("** (protocol ")
                .append(NcmAblationEvaluation.PROTOCOL_ID).append(", contract ").append(NcmAblationEvaluation.CONTRACT)
                .append("). G3 (C): ").append(outcome.g3().go() ? "GO" : "NO-GO").append("; H5 (E): ")
                .append(outcome.g5().holds() ? "holds" : "negative").append(".\n\n");

        sb.append("### Registered G3 rule per arm\n\n| Arm | Every repeat |");
        for (RepeatResult repeat : outcome.repeats()) {
            sb.append(" Repeat ").append(repeat.number()).append(" |");
        }
        sb.append("\n| :--- | :--- |").append(" :--- |".repeat(outcome.repeats().size())).append('\n');
        for (int a = 0; a < NcmAblationEvaluation.ARMS.size(); a++) {
            sb.append("| ").append(NcmAblationEvaluation.ARMS.get(a)).append(" | ")
                    .append(outcome.armGo()[a] ? "pass" : "fail").append(" |");
            for (RepeatResult repeat : outcome.repeats()) {
                sb.append(' ').append(repeat.arms().get(a).gatePassed() ? "pass" : "fail").append(" |");
            }
            sb.append('\n');
        }
        sb.append('\n');

        for (RepeatResult repeat : outcome.repeats()) {
            sb.append("### Medium workload, repeat ").append(repeat.number()).append(" (test seed ")
                    .append(NcmAblationEvaluation.hex(repeat.seed())).append(")\n\n");
            sb.append("| Arm | H3: acc - acc(A) | H6: acc - forced | Coverage | Covered acc | Forced acc | Top-2 | MRR "
                    + "| Root cov / acc | ECE forced / covered | Abstention prec / rec | N11 / 20 dB uncovered "
                    + "| Comparisons / window |\n");
            sb.append("| :--- |").append(" ---: |".repeat(12)).append('\n');
            for (ArmResult arm : repeat.arms()) {
                sb.append("| ").append(arm.name()).append(" | ")
                        .append(point(arm.coveredAccuracy() - arm.aOnCovered(), arm.h3())).append(" | ")
                        .append(point(arm.coveredAccuracy() - arm.forcedAccuracy(), arm.h6())).append(" | ")
                        .append(point(arm.coverage(), arm.coverageInterval())).append(" | ")
                        .append(format(arm.coveredAccuracy())).append(" | ").append(format(arm.forcedAccuracy()))
                        .append(" | ").append(format(arm.top2())).append(" | ").append(format(arm.mrr())).append(" | ")
                        .append(format(arm.rootCoverage())).append(" / ").append(format(arm.rootAccuracy()))
                        .append(" | ").append(format(arm.forcedEce())).append(" / ").append(format(arm.coveredEce()))
                        .append(" | ").append(format(arm.abstentionPrecision())).append(" / ")
                        .append(format(arm.abstentionRecall())).append(" | ").append(format(arm.noiseUncovered()))
                        .append(" / ").append(format(arm.cleanUncovered())).append(" | ")
                        .append(String.format(Locale.ROOT, "%.1f", arm.comparisonsPerWindow())).append(" |\n");
            }
            sb.append("\n| Ablation (paired on every window) | Point | 95% interval |\n| :--- | ---: | :--- |\n");
            ArmResult c = repeat.arms().get(NcmAblationEvaluation.C);
            for (ArmResult arm : repeat.arms()) {
                sb.append("| Hierarchy: acc(").append(arm.name()).append(") - acc(").append(arm.name())
                        .append("-flat) | ").append(format(arm.coveredAccuracy() - arm.flatAccuracy())).append(" | ")
                        .append(format(arm.h4())).append(" |\n");
            }
            for (ArmResult arm : repeat.arms().subList(1, repeat.arms().size())) {
                sb.append("| Features: forced(").append(arm.name()).append(") - forced(C) | ")
                        .append(format(arm.forcedAccuracy() - c.forcedAccuracy())).append(" | ")
                        .append(format(arm.forcedVsC())).append(" |\n");
                sb.append("| Features: rate(").append(arm.name()).append(") - rate(C) | ")
                        .append(format(arm.rate() - c.rate())).append(" | ").append(format(arm.rateVsC()))
                        .append(" |\n");
            }
            sb.append("\n| Arm |");
            for (double snr : protocol.g3().snrLevels()) {
                sb.append(' ').append(snr).append(" dB coverage / acc |");
            }
            sb.append("\n| :--- |").append(" ---: |".repeat(protocol.g3().snrLevels().size())).append('\n');
            for (ArmResult arm : repeat.arms()) {
                sb.append("| ").append(arm.name()).append(" |");
                for (int s = 0; s < arm.coverageByLevel().length; s++) {
                    sb.append(' ').append(format(arm.coverageByLevel()[s])).append(" / ")
                            .append(format(arm.accuracyByLevel()[s])).append(" |");
                }
                sb.append('\n');
            }
            sb.append("\nRisk-coverage (forced accuracy at 10% .. 100% coverage by forced score):\n\n");
            for (ArmResult arm : repeat.arms()) {
                sb.append("- ").append(arm.name()).append(": ").append(curve(arm.riskCoverage())).append('\n');
            }
            sb.append('\n');
        }

        sb.append("### Controls A, A-oracle and B (G3 harness, pooled over SNR levels)\n\n")
                .append("| Repeat | A on C-covered | A-oracle (informational) | B | A on every window |\n")
                .append("| ---: | ---: | ---: | ---: | ---: |\n");
        for (NextConceptG3Evaluation.RepeatResult repeat : outcome.g3().repeats()) {
            int pooled = repeat.coverageByLevel().length - 1;
            sb.append("| ").append(repeat.number()).append(" | ").append(format(repeat.armAccuracy()[1][pooled]))
                    .append(" | ").append(format(repeat.armAccuracy()[2][pooled])).append(" | ")
                    .append(format(repeat.armAccuracy()[3][pooled])).append(" | ")
                    .append(format(repeat.armAccuracy()[6][pooled])).append(" |\n");
        }
        sb.append("\n### Control E (G5 harness)\n\n| Repeat | rate(E) - rate(C) | 95% interval | E coverage | Gate |\n")
                .append("| ---: | ---: | :--- | ---: | :--- |\n");
        for (NextConceptG5Evaluation.RepeatResult repeat : outcome.g5().repeats()) {
            sb.append("| ").append(repeat.number()).append(" | ")
                    .append(format(repeat.rate()[NextConceptG5Evaluation.Arm.E]
                            - repeat.rate()[NextConceptG5Evaluation.Arm.C])).append(" | ")
                    .append(format(repeat.h5())).append(" | ")
                    .append(format(repeat.coverage()[NextConceptG5Evaluation.Arm.E])).append(" | ")
                    .append(repeat.gatePassed() ? "pass" : "fail").append(" |\n");
        }

        LargeWorkloadEvaluation.Result large = outcome.large();
        sb.append("\n### Large workload (informational)\n\n")
                .append("| Arm | Leaf coverage | Leaf acc | Depth-1 acc | Root acc | Forced acc | Top-")
                .append(LargeWorkloadEvaluation.TOP_K).append(" | MRR | ECE forced | Comparisons / window (max) |\n")
                .append("| :--- |").append(" ---: |".repeat(9)).append('\n');
        large(sb, "C", large.cScore());
        large(sb, "D-haar (L=" + protocol.large().haarLevels() + ")", large.dScore());
        sb.append("\nControl A ").append(format(large.aAccuracy())).append(", control B ")
                .append(format(large.bAccuracy())).append(" over ").append(large.answerable())
                .append(" windows with a successor; C snapshot ").append(large.c().retainedBytes())
                .append(" bytes, D snapshot ").append(large.d().retainedBytes()).append(" bytes.\n\n")
                .append("Risk-coverage: C ").append(curve(large.cScore().riskCoverage())).append("; D ")
                .append(curve(large.dScore().riskCoverage())).append(".\n\n");

        sb.append("### End-to-end cost per window\n\n| Scenario | Scale | Windows / iteration | Median per window | "
                + "Median per iteration | Allocated / window | Snapshot bytes |\n")
                .append("| :--- | :--- | ---: | ---: | ---: | ---: | ---: |\n");
        for (BenchmarkRunResult result : outcome.resources()) {
            int windows = Integer.parseInt(result.diagnostics().get("windowsPerIteration"));
            sb.append("| `").append(result.benchmarkName()).append("` | ").append(result.workloadScale()).append(" | ")
                    .append(windows).append(" | ").append(micros(result.latency().medianNanos() / windows))
                    .append(" | ").append(micros(result.latency().medianNanos())).append(" | ")
                    .append(String.format(Locale.ROOT, "%,.0f B", result.allocation().bytesPerOp())).append(" | ")
                    .append(result.diagnostics().get("snapshotRetainedBytes")).append(" |\n");
        }
        return sb.append('\n').toString();
    }

    private void large(StringBuilder sb, String name, ArmScore score) {
        sb.append("| ").append(name).append(" | ").append(format(score.leafCoverage())).append(" | ")
                .append(format(score.leafAccuracy())).append(" | ").append(format(score.midAccuracy())).append(" | ")
                .append(format(score.rootAccuracy())).append(" | ").append(format(score.forcedAccuracy()))
                .append(" | ").append(format(score.topK())).append(" | ").append(format(score.mrr())).append(" | ")
                .append(format(score.ece())).append(" | ")
                .append(String.format(Locale.ROOT, "%.1f (%d)", score.comparisonsPerWindow(), score.maxComparisons()))
                .append(" |\n");
    }

    private String point(double value, Interval interval) {
        return format(value) + " " + format(interval);
    }

    private String curve(double[] values) {
        var parts = new StringBuilder();
        for (double value : values) {
            parts.append(parts.isEmpty() ? "" : " ").append(String.format(Locale.ROOT, "%.3f", value));
        }
        return parts.toString();
    }

    private String micros(double nanos) {
        return String.format(Locale.ROOT, "%,.1f µs", nanos / 1_000.0);
    }

    String resultsJson(Outcome outcome) {
        var sb = new StringBuilder("{ \"protocol\": \"").append(NcmAblationEvaluation.PROTOCOL_ID)
                .append("\", \"verdict\": \"").append(outcome.verdict()).append("\", \"g3Go\": ")
                .append(outcome.g3().go()).append(", \"h5Holds\": ").append(outcome.g5().holds())
                .append(", \"arms\": [");
        for (int a = 0; a < NcmAblationEvaluation.ARMS.size(); a++) {
            sb.append(a == 0 ? "" : ", ").append("{ \"name\": \"").append(NcmAblationEvaluation.ARMS.get(a))
                    .append("\", \"gateEveryRepeat\": ").append(outcome.armGo()[a]).append(", \"n11Uncovered\": ")
                    .append(outcome.noiseUncovered()[a]).append(" }");
        }
        sb.append("], \"repeats\": [");
        for (int r = 0; r < outcome.repeats().size(); r++) {
            RepeatResult repeat = outcome.repeats().get(r);
            sb.append(r == 0 ? "\n" : ",\n").append("    { \"repeat\": ").append(repeat.number())
                    .append(", \"testSeed\": ").append(repeat.seed()).append(", \"arms\": [");
            for (int a = 0; a < repeat.arms().size(); a++) {
                ArmResult arm = repeat.arms().get(a);
                sb.append(a == 0 ? "" : ", ").append("{ \"name\": \"").append(arm.name())
                        .append("\", \"gate\": ").append(arm.gatePassed())
                        .append(", \"h3\": ").append(interval(arm.h3()))
                        .append(", \"h6\": ").append(interval(arm.h6()))
                        .append(", \"h4\": ").append(interval(arm.h4()))
                        .append(", \"forcedVsC\": ").append(interval(arm.forcedVsC()))
                        .append(", \"rateVsC\": ").append(interval(arm.rateVsC()))
                        .append(", \"coverage\": ").append(arm.coverage())
                        .append(", \"coveredAccuracy\": ").append(arm.coveredAccuracy())
                        .append(", \"forcedAccuracy\": ").append(arm.forcedAccuracy())
                        .append(", \"mrr\": ").append(arm.mrr())
                        .append(", \"forcedEce\": ").append(arm.forcedEce())
                        .append(", \"coverageBySnr\": ").append(Arrays.toString(arm.coverageByLevel()))
                        .append(", \"accuracyBySnr\": ").append(Arrays.toString(arm.accuracyByLevel()))
                        .append(" }");
            }
            sb.append("] }");
        }
        LargeWorkloadEvaluation.Result large = outcome.large();
        sb.append("\n  ], \"large\": { \"c\": ").append(largeJson(large.cScore())).append(", \"d\": ")
                .append(largeJson(large.dScore())).append(", \"a\": ").append(large.aAccuracy())
                .append(", \"b\": ").append(large.bAccuracy()).append(", \"cRetainedBytes\": ")
                .append(large.c().retainedBytes()).append(", \"dRetainedBytes\": ").append(large.d().retainedBytes())
                .append(" }, \"h5\": [");
        List<NextConceptG5Evaluation.RepeatResult> g5 = outcome.g5().repeats();
        for (int r = 0; r < g5.size(); r++) {
            sb.append(r == 0 ? "" : ", ").append(interval(g5.get(r).h5()));
        }
        return sb.append("] }").toString();
    }

    private String largeJson(ArmScore score) {
        return "{ \"leafCoverage\": " + score.leafCoverage() + ", \"leafAccuracy\": " + score.leafAccuracy()
                + ", \"midAccuracy\": " + score.midAccuracy() + ", \"rootAccuracy\": " + score.rootAccuracy()
                + ", \"forcedAccuracy\": " + score.forcedAccuracy() + ", \"topK\": " + score.topK() + ", \"mrr\": "
                + score.mrr() + ", \"ece\": " + score.ece() + ", \"maxComparisons\": " + score.maxComparisons() + " }";
    }

    private String interval(Interval interval) {
        return "{ \"lower\": " + interval.lower() + ", \"upper\": " + interval.upper() + " }";
    }

    /** Writes {@code ncm-ablation.json} and {@code .md} into {@code outputDir}. */
    void write(NcmAblationReport report, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            var json = outputDir.resolve(NAME + ".json");
            var markdown = outputDir.resolve(NAME + ".md");
            Files.writeString(json, report.toJson());
            Files.writeString(markdown, report.toMarkdown());
            System.out.println("NCM ablation reports saved to:\n  " + json.toAbsolutePath() + "\n  "
                    + markdown.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write NCM ablation reports", e);
        }
    }

    private String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}
