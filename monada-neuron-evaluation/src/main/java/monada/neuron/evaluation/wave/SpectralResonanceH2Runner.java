package monada.neuron.evaluation.wave;

import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.HostProbes;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.ArmResult;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.Outcome;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.Protocol;
import monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.RepeatResult;

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

import static monada.neuron.evaluation.wave.SpectralResonanceH2Evaluation.format;

/**
 * CLI for the H2 evaluation of issue #125 (gate G2), protocol {@code docs/benchmarks/spectral-resonance-h2.md}.
 *
 * <p>Options: {@code --quick} (first repeat, 8 sequences, 200 resamples) and {@code --output-dir <dir>}. Exit status:
 * 0 when every semantic check passes, whatever the G2 verdict; 1 when a check fails; 2 for invalid arguments; 4 when
 * the harness itself failed, so a crash is never read as a verdict.
 */
public final class SpectralResonanceH2Runner {

    /** Exit status when a semantic check failed. */
    static final int CHECK_FAILED = 1;
    /** Exit status for invalid arguments. */
    static final int INVALID_ARGUMENTS = 2;
    /** Exit status when the harness failed: an unexpected exception, or the report could not be written. */
    static final int HARNESS_FAILURE = 4;

    private static final String NAME = "spectral-resonance-h2";

    SpectralResonanceH2Runner() {
    }

    /** CLI entrypoint; exits with {@link #execute}'s status of a new runner when it is not 0. */
    public static void main(String[] args) {
        int status = new SpectralResonanceH2Runner().execute(args);
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
        var protocol = quick
                ? SpectralResonanceH2Evaluation.REGISTERED.quick()
                : SpectralResonanceH2Evaluation.REGISTERED;
        boolean quickMode = quick;
        return report(() -> run(protocol, quickMode), outputDir);
    }

    /**
     * Runs an evaluation, prints its report and writes it; returns 0 when every check passed, {@link #CHECK_FAILED}
     * when one failed and {@link #HARNESS_FAILURE} when running or writing threw.
     */
    int report(Supplier<SpectralResonanceH2Report> evaluation, Path outputDir) {
        SpectralResonanceH2Report report;
        try {
            report = evaluation.get();
            System.out.println(report.toMarkdown());
            write(report, outputDir);
        } catch (RuntimeException | Error failure) {
            // An Error (out of memory, stack overflow) is a harness failure too, never a check verdict.
            System.err.println("The H2 harness failed; no verdict was reached: " + failure);
            return HARNESS_FAILURE;
        }
        if (!report.allPassed()) {
            System.err.println("H2 semantic checks failed; the G2 verdict cannot be trusted.");
            return CHECK_FAILED;
        }
        return 0;
    }

    /** Runs the evaluation under {@code protocol} and assembles the report. */
    SpectralResonanceH2Report run(Protocol protocol, boolean quick) {
        var h2 = new SpectralResonanceH2Evaluation(protocol, quick);
        Outcome outcome = h2.run();
        var probes = new HostProbes();
        var metadata = new LinkedHashMap<>(outcome.metadata());
        metadata.put("env.cpuModel", probes.cpuModel(Path.of("/proc/cpuinfo")));
        metadata.put("neuron.commit", probes.gitCommit(Path.of("").toAbsolutePath()));
        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(), h2.runConfiguration(),
                List.of(), SpectralResonanceH2Report.TITLE);
        return new SpectralResonanceH2Report(evaluation, outcome.checks(), metadata,
                resultsJson(protocol, outcome), resultsMarkdown(protocol, outcome));
    }

    String resultsMarkdown(Protocol protocol, Outcome outcome) {
        var sb = new StringBuilder("## H2 Results\n\n");
        sb.append("G2 verdict: **").append(outcome.go() ? "GO" : "NO-GO").append("** (margin ")
                .append(format(protocol.margin())).append("; every repeat's lower bound of AUC(aggregate) - "
                        + "AUC(scalar) must exceed it)\n\n");
        for (RepeatResult repeat : outcome.repeats()) {
            sb.append("### Repeat ").append(repeat.number()).append(" (test seed 0x")
                    .append(Long.toHexString(repeat.seed()).toUpperCase(Locale.ROOT)).append(", ")
                    .append(repeat.pairsPerLevel()).append(" pairs per level, ").append(repeat.positives())
                    .append(" same-concept)\n\n");
            sb.append("| Arm | Gate | Pooled AUC | 95% interval |");
            protocol.snrLevels().forEach(snr -> sb.append(' ').append(decibels(snr)).append(" dB |"));
            sb.append("\n| :--- | :--- | ---: | :--- |");
            protocol.snrLevels().forEach(snr -> sb.append(" ---: |"));
            sb.append('\n');
            for (ArmResult arm : repeat.arms()) {
                sb.append("| `").append(arm.arm().label()).append("` | ").append(arm.arm().gate() ? "yes" : "no")
                        .append(" | ").append(format(arm.auc())).append(" | ").append(format(arm.interval()))
                        .append(" |");
                for (double auc : arm.levelAucs()) {
                    sb.append(' ').append(format(auc)).append(" |");
                }
                sb.append('\n');
            }
            sb.append("| aggregate - scalar | gate | ")
                    .append(format(repeat.arm(H2Arm.AGGREGATE).auc() - repeat.arm(H2Arm.SCALAR).auc()))
                    .append(" | ").append(format(repeat.difference())).append(" |");
            repeat.levelDifferences().forEach(interval -> sb.append(' ').append(format(interval)).append(" |"));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    String resultsJson(Protocol protocol, Outcome outcome) {
        var sb = new StringBuilder("{ \"margin\": ").append(protocol.margin())
                .append(", \"go\": ").append(outcome.go()).append(", \"snrLevelsDb\": ")
                .append(protocol.snrLevels()).append(", \"repeats\": [");
        for (int r = 0; r < outcome.repeats().size(); r++) {
            RepeatResult repeat = outcome.repeats().get(r);
            sb.append(r == 0 ? "\n" : ",\n").append("    { \"repeat\": ").append(repeat.number())
                    .append(", \"testSeed\": ").append(repeat.seed())
                    .append(", \"pairsPerLevel\": ").append(repeat.pairsPerLevel())
                    .append(", \"positivesPerLevel\": ").append(repeat.positives())
                    .append(", \"difference\": { \"lower\": ").append(repeat.difference().lower())
                    .append(", \"upper\": ").append(repeat.difference().upper()).append(" }, \"arms\": [");
            for (int a = 0; a < repeat.arms().size(); a++) {
                ArmResult arm = repeat.arms().get(a);
                sb.append(a == 0 ? "" : ", ").append("{ \"arm\": \"").append(arm.arm().label())
                        .append("\", \"gate\": ").append(arm.arm().gate())
                        .append(", \"auc\": ").append(arm.auc())
                        .append(", \"lower\": ").append(arm.interval().lower())
                        .append(", \"upper\": ").append(arm.interval().upper())
                        .append(", \"levelAucs\": ").append(Arrays.toString(arm.levelAucs())).append(" }");
            }
            sb.append("] }");
        }
        return sb.append("\n  ] }").toString();
    }

    /** Writes {@code spectral-resonance-h2.json} and {@code .md} into {@code outputDir}. */
    void write(SpectralResonanceH2Report report, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            var json = outputDir.resolve(NAME + ".json");
            var markdown = outputDir.resolve(NAME + ".md");
            Files.writeString(json, report.toJson());
            Files.writeString(markdown, report.toMarkdown());
            System.out.println("H2 reports saved to:\n  " + json.toAbsolutePath() + "\n  "
                    + markdown.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write H2 reports", e);
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
