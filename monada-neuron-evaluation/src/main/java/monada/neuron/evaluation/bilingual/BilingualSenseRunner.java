package monada.neuron.evaluation.bilingual;

import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.ArmResult;
import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.DirectionResult;
import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.Estimate;
import monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.Outcome;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.evaluation.metrics.HostProbes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

import static monada.neuron.evaluation.bilingual.BilingualSenseEvaluation.format;

/**
 * CLI of the {@code bilingual-sense/2} harness (issue #185).
 *
 * <p>Step 8 may run only after the v2 prerequisites hold (agent configuration commit, steps 2 to 7 recorded, held-out
 * gold and sealed-log hashes committed). None holds yet, so the only runnable mode is {@code --synthetic}, the harness
 * self-test over generated data, which produces no result. Without it the runner prints the prerequisites and exits
 * with {@link #NOT_READY}.
 *
 * <p>Options: {@code --synthetic}, {@code --seed <long>}, {@code --quick} (200 resamples and no cost passes, so check {@code budget} fails: a smoke run, never a verdict) and
 * {@code --output-dir <dir>}. Exit status: 0 when every check passes; 1 when a check fails; 2 for invalid arguments;
 * 3 when step 8 is not ready; 4 when the harness itself failed.
 */
public final class BilingualSenseRunner {

    static final int CHECK_FAILED = 1;
    static final int INVALID_ARGUMENTS = 2;
    static final int NOT_READY = 3;
    static final int HARNESS_FAILURE = 4;

    private static final String NAME = "bilingual-sense-selftest";
    private static final int QUICK_RESAMPLES = 200;

    /** The v2 prerequisites of step 8 ("Effect on #185 and Epic #182"). */
    static final List<String> PREREQUISITES = List.of(
            "the agent configuration commit (A1, A2, J with exact model ids) is merged and annotation.availability passes",
            "steps 2 to 7 have run under bilingual-sense/2, with every gate and diagnostic recorded in the feasibility "
                    + "report",
            "the held-out gold and sealed-log SHA-256 are committed",
            "a dataset loader for the step-7 artifacts exists (their file format is fixed by the offline orchestrator, "
                    + "which does not exist yet)");

    BilingualSenseRunner() {
    }

    /** CLI entrypoint; exits with {@link #execute}'s status when it is not 0. */
    public static void main(String[] args) {
        int status = new BilingualSenseRunner().execute(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    /** Parses the arguments, runs, prints and writes the report; returns the exit status. */
    int execute(String[] args) {
        var synthetic = false;
        var quick = false;
        var seed = SyntheticSenseWorkload.SEED;
        var outputDir = Path.of("build/reports/benchmarks");
        try {
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--synthetic" -> synthetic = true;
                    case "--quick" -> quick = true;
                    case "--seed" -> seed = Long.decode(value(args, ++i, "--seed"));
                    case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: --synthetic [--seed <long>] [--quick] [--output-dir <dir>]");
            return INVALID_ARGUMENTS;
        }
        if (!synthetic) {
            System.err.println("Step 8 of bilingual-sense/2 cannot run yet; the held-out split stays sealed. Missing:");
            PREREQUISITES.forEach(prerequisite -> System.err.println("  - " + prerequisite));
            System.err.println("Run --synthetic for the harness self-test (no result).");
            return NOT_READY;
        }
        long chosenSeed = seed;
        int resamples = quick ? QUICK_RESAMPLES : ClusterBootstrap.RESAMPLES;
        BilingualSenseReport report;
        try {
            var evaluation = new BilingualSenseEvaluation(resamples, !quick);
            Outcome outcome = evaluation.run(() -> new SyntheticSenseWorkload(chosenSeed).generate());
            report = reportOf(outcome, new RunConfiguration(chosenSeed, quick, BilingualSenseEvaluation.WARMUP,
                    BilingualSenseEvaluation.MEASURED, "one op per held-out item"));
            System.out.println(report.toMarkdown());
            write(report, outputDir);
        } catch (RuntimeException | Error failure) {
            System.err.println("The bilingual sense harness failed; no verdict was reached: " + failure);
            return HARNESS_FAILURE;
        }
        if (!report.allPassed()) {
            System.err.println("Bilingual sense checks failed; the run is untrustworthy.");
            return CHECK_FAILED;
        }
        return 0;
    }

    /** Assembles the report of a finished run. */
    BilingualSenseReport reportOf(Outcome outcome, RunConfiguration configuration) {
        var probes = new HostProbes();
        var metadata = new LinkedHashMap<>(outcome.metadata());
        metadata.put("env.cpuModel", probes.cpuModel(Path.of("/proc/cpuinfo")));
        metadata.put("neuron.commit", probes.gitCommit(Path.of("").toAbsolutePath()));
        metadata.put("fingerprint", outcome.fingerprint());
        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(), configuration,
                outcome.resources(), BilingualSenseReport.SYNTHETIC_TITLE);
        return new BilingualSenseReport(evaluation, new ArrayList<EvaluationCheck>(outcome.checks()), metadata,
                resultsJson(outcome), resultsMarkdown(outcome));
    }

    String resultsMarkdown(Outcome outcome) {
        var sb = new StringBuilder("## Self-Test Results\n\n")
                .append("**Synthetic data: these numbers verify the harness, not any hypothesis.** The sentences and "
                        + "gold are generated; no corpus, annotation or held-out split of `bilingual-sense/2` was read. "
                        + "Arm W is omitted (optional under the protocol).\n\n");
        for (DirectionResult direction : outcome.directions()) {
            sb.append("### ").append(direction.direction().label()).append(": ").append(direction.verdict())
                    .append("\n\n").append(direction.reason()).append(".\n\n");
            direction.notes().forEach(note -> sb.append("- ").append(note).append('\n'));
            if (direction.arms().isEmpty()) {
                sb.append('\n');
                continue;
            }
            sb.append("\n| Arm | Selected (validation) | Macro [95%] | Forced | Coverage | Selective | Abstention P / R "
                    + "| AURC [95%] | AUROC [95%] | ECE | Brier | Probe | Gap |\n| :--- | :--- |")
                    .append(" ---: |".repeat(11)).append('\n');
            for (ArmResult arm : direction.arms()) {
                Map<String, OptionalDouble> v = arm.values();
                sb.append("| ").append(arm.arm()).append(" | ").append(arm.selectionDescription()).append(" | ")
                        .append(estimate(arm.macro())).append(" | ").append(format(v.get("forcedAccuracy")))
                        .append(" | ").append(format(v.get("coverage"))).append(" | ")
                        .append(format(v.get("selectiveAccuracy"))).append(" | ")
                        .append(format(v.get("abstentionPrecision"))).append(" / ")
                        .append(format(v.get("abstentionRecall"))).append(" | ").append(estimate(arm.aurc()))
                        .append(" | ").append(estimate(arm.auroc())).append(" | ").append(format(v.get("ece")))
                        .append(" | ").append(format(v.get("brier"))).append(" | ")
                        .append(format(v.get("memorizationProbe"))).append(" | ")
                        .append(format(v.get("generalizationGap"))).append(" |\n");
            }
            sb.append("\n| Hypothesis | vs A | vs B | vs C | Margin |\n| :--- | ---: | ---: | ---: | :--- |\n")
                    .append("| H1: Macro(N) - Macro(X) | ").append(estimate(direction.h1().get("A"))).append(" | ")
                    .append(estimate(direction.h1().get("B"))).append(" | ").append(estimate(direction.h1().get("C")))
                    .append(" | lower > ").append(BilingualSenseEvaluation.H1_MARGIN).append(" |\n")
                    .append("| H2a: AURC(X) - AURC(N) | ").append(estimate(direction.h2a().get("A"))).append(" | ")
                    .append(estimate(direction.h2a().get("B"))).append(" | ")
                    .append(estimate(direction.h2a().get("C"))).append(" | lower > ")
                    .append(BilingualSenseEvaluation.H2A_MARGIN).append(" |\n\n")
                    .append("H2b: AUROC(N) lower bound > ").append(BilingualSenseEvaluation.H2B_BOUND)
                    .append("; Macro_const ").append(format(direction.macroConstant())).append(".\n\n");
            sb.append("Per scored sense (reported, never gated):\n\n");
            for (ArmResult arm : direction.arms()) {
                var parts = new ArrayList<String>();
                arm.perSense().forEach((sense, value) -> parts.add(sense + " " + format(value)));
                sb.append("- ").append(arm.arm()).append(": ").append(String.join(", ", parts)).append('\n');
            }
            sb.append("\nStrata:\n\n");
            for (ArmResult arm : direction.arms()) {
                sb.append("- ").append(arm.arm()).append(": ");
                var parts = new ArrayList<String>();
                arm.strata().forEach((name, summary) -> parts.add(name + " (" + summary + ")"));
                sb.append(String.join("; ", parts)).append('\n');
            }
            sb.append("\n| Arm | Fit | Selection | Median / item | p95 / item | Allocated / item | Retained state |\n")
                    .append("| :--- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (ArmResult arm : direction.arms()) {
                var cost = arm.cost();
                sb.append("| ").append(arm.arm()).append(" | ").append(millis(cost.fitNanos())).append(" | ")
                        .append(millis(cost.selectNanos())).append(" | ").append(micros(cost.medianNanos()))
                        .append(" | ").append(micros(cost.p95Nanos())).append(" | ")
                        .append(Double.isNaN(cost.bytesPerItem()) ? "n/a"
                                : String.format(Locale.ROOT, "%,.0f B", cost.bytesPerItem()))
                        .append(" | ").append(String.format(Locale.ROOT, "%,d B", cost.retainedBytes())).append(" |\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    String resultsJson(Outcome outcome) {
        var sb = new StringBuilder("{ \"protocol\": \"").append(BilingualSenseEvaluation.PROTOCOL_ID)
                .append("\", \"synthetic\": true, \"fingerprint\": \"").append(outcome.fingerprint())
                .append("\", \"directions\": [");
        for (int d = 0; d < outcome.directions().size(); d++) {
            DirectionResult direction = outcome.directions().get(d);
            sb.append(d == 0 ? "" : ", ").append("{ \"direction\": \"").append(direction.direction().label())
                    .append("\", \"verdict\": \"").append(direction.verdict()).append("\", \"arms\": [");
            for (int a = 0; a < direction.arms().size(); a++) {
                ArmResult arm = direction.arms().get(a);
                sb.append(a == 0 ? "" : ", ").append("{ \"arm\": \"").append(arm.arm()).append("\", \"selected\": \"")
                        .append(arm.selectionDescription()).append("\", \"macro\": ").append(json(arm.macro()))
                        .append(", \"aurc\": ").append(json(arm.aurc())).append(", \"auroc\": ")
                        .append(json(arm.auroc()));
                arm.values().forEach((name, value) -> sb.append(", \"").append(name).append("\": ")
                        .append(value.isPresent() ? Double.toString(value.getAsDouble()) : "null"));
                sb.append(" }");
            }
            sb.append("], \"h1\": {");
            appendEstimates(sb, direction.h1());
            sb.append("}, \"h2a\": {");
            appendEstimates(sb, direction.h2a());
            sb.append("} }");
        }
        return sb.append("] }").toString();
    }

    private void appendEstimates(StringBuilder sb, Map<String, Estimate> estimates) {
        var first = true;
        for (var entry : estimates.entrySet()) {
            sb.append(first ? " " : ", ").append('"').append(entry.getKey()).append("\": ").append(json(entry.getValue()));
            first = false;
        }
        sb.append(' ');
    }

    private String json(Estimate estimate) {
        return "{ \"point\": " + (estimate.point().isPresent() ? Double.toString(estimate.point().getAsDouble()) : "null")
                + ", \"lower\": " + number(estimate.interval().lower()) + ", \"upper\": "
                + number(estimate.interval().upper()) + ", \"undefinedResamples\": " + estimate.interval().undefined()
                + " }";
    }

    private String number(double value) {
        return Double.isNaN(value) ? "null" : Double.toString(value);
    }

    private String estimate(Estimate estimate) {
        return estimate == null ? "n/a" : format(estimate.point()) + " [" + format(estimate.interval().lower()) + ", "
                + format(estimate.interval().upper()) + "]";
    }

    private String millis(long nanos) {
        return String.format(Locale.ROOT, "%,.1f ms", nanos / 1_000_000.0);
    }

    private String micros(long nanos) {
        return nanos < 0 ? "n/a" : String.format(Locale.ROOT, "%,.1f µs", nanos / 1_000.0);
    }

    /** Writes {@code bilingual-sense-selftest.json} and {@code .md} into {@code outputDir}. */
    void write(BilingualSenseReport report, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            var json = outputDir.resolve(NAME + ".json");
            var markdown = outputDir.resolve(NAME + ".md");
            Files.writeString(json, report.toJson());
            Files.writeString(markdown, report.toMarkdown());
            System.out.println("Bilingual sense self-test reports saved to:\n  " + json.toAbsolutePath() + "\n  "
                    + markdown.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write bilingual sense reports", e);
        }
    }

    private String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}
