package monada.neuron.evaluation.integration;

import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.TreeMap;

/**
 * CLI for the end-to-end Neuron + Resonance Store evaluation.
 *
 * <p>Options: {@code --quick}, {@code --seed <n>}, {@code --output-dir <dir>}. The exit status is
 * non-zero only when a semantic check fails; timings never affect it.
 */
public final class ResonanceStoreIntegrationRunner {

    private static final String NEURON_VERSION_PROPERTY = "monada.neuron.version";
    private static final String STORE_DIRECTORY_PROPERTY = "monada.resonance.store.dir";

    private ResonanceStoreIntegrationRunner() {
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
            var json = outputDir.resolve("resonance-store-integration.json");
            var markdown = outputDir.resolve("resonance-store-integration.md");
            Files.writeString(json, report.toJson());
            Files.writeString(markdown, report.toMarkdown());
            System.out.println("Integration reports saved to:\n  " + json.toAbsolutePath() + "\n  " + markdown.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write integration reports", e);
        }
        if (!report.allPassed()) {
            System.err.println("Semantic integration checks failed.");
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
    public static ResonanceStoreIntegrationReport run(long seed, boolean quick) {
        var outcome = new ResonanceStoreIntegrationEvaluation(seed, quick).run();
        var metadata = new TreeMap<>(outcome.metadata());
        metadata.put("neuron.version", System.getProperty(NEURON_VERSION_PROPERTY, "unknown"));
        metadata.put("neuron.commit", StoreMetadata.gitCommit(Path.of(".").toAbsolutePath().normalize()));
        var storeDirectory = System.getProperty(STORE_DIRECTORY_PROPERTY);
        metadata.put("store.checkoutCommit",
                StoreMetadata.gitCommit(storeDirectory == null ? null : Path.of(storeDirectory)));
        metadata.put("store.integration", "embedded MonadaMemory through ResonanceStoreMemoryAdapter (ADR 0018)");

        var evaluation = new EvaluationReport(
                Instant.now(),
                EnvironmentMetadata.current(),
                new RunConfiguration(
                        seed,
                        quick,
                        0,
                        0,
                        "Iteration counts differ per row: see warmupIterations and measurementIterations in each"
                                + " row's diagnostics; 1 op per iteration"),
                outcome.results(),
                ResonanceStoreIntegrationReport.TITLE);
        return new ResonanceStoreIntegrationReport(evaluation, outcome.checks(), metadata);
    }
}
