package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.integration.StoreMetadata;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecallDiagnosis.PointDiagnosis;
import monada.neuron.evaluation.knowledge.RecallDiagnosisLadder.Rung;
import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.resonance.adapter.ResonanceStoreRecall;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * CLI of the B2 recall diagnosis (Issue #135): research-only, opt-in and evaluation-only. Builds the controlled ladder
 * ({@link RecallDiagnosisLadder}), imports each point into its own fresh Store root, verifies the #107 observation and
 * diagnoses where relevant evidence is lost between the per-token Store recalls and B2's merged top {@code k}
 * ({@link HtmlKnowledgeRecallDiagnosis}). It changes no production path: the adapter, the codec and the Store run
 * unchanged, and every merge alternative is replayed offline.
 *
 * <p>Options: {@code --points base-25,base-33,n100,n500} (a subset in ladder order; default all), {@code --seed <n>}
 * (generator seed, default {@value ScalingDistractorGenerator#DEFAULT_SEED}), {@code --output-dir <dir>} (default
 * {@code build/reports/benchmarks}) and {@code --store-root <dir>} (keep the point roots, absent or empty). Outputs:
 * {@code html-knowledge-recall-diagnosis.json} (verdicts, metadata and the payload under {@code diagnosis}) and
 * {@code html-knowledge-recall-diagnosis.md}. Exit status: {@code 0} all checks passed, {@code 1} a semantic check
 * failed, {@code 2} invalid usage or rejected input.
 */
public final class HtmlKnowledgeRecallDiagnosisRunner {

    /** Report title. */
    public static final String TITLE = "Monada Neuron HTML Knowledge B2 Recall Diagnosis";

    /** The study label: a diagnostic over the protocol, not a protocol extension. */
    static final String STUDY = "b2-recall-diagnosis (issue #135, research-only, no protocol version change)";

    static final String REPORT_JSON = "html-knowledge-recall-diagnosis.json";
    static final String REPORT_MARKDOWN = "html-knowledge-recall-diagnosis.md";

    private static final String NEURON_VERSION_PROPERTY = "monada.neuron.version";
    private static final String STORE_DIRECTORY_PROPERTY = "monada.resonance.store.dir";
    private static final String USAGE = "Usage: [--points base-25,base-33,n100,n500] [--seed <n>] [--output-dir <dir>]"
            + " [--store-root <dir>]";

    private HtmlKnowledgeRecallDiagnosisRunner() {
    }

    /**
     * Parsed options.
     *
     * @param outputDir report directory
     * @param storeRoot kept directory of the point roots, or empty for a deleted temporary directory
     * @param rungs the ladder points, in ladder order
     * @param seed the generator seed
     */
    public record Options(Path outputDir, Optional<Path> storeRoot, List<Rung> rungs, long seed) {

        public Options {
            Objects.requireNonNull(outputDir, "outputDir must not be null");
            Objects.requireNonNull(storeRoot, "storeRoot must not be null");
            rungs = List.copyOf(rungs);
            if (storeRoot.isPresent()) {
                var a = HtmlKnowledgeIngestionRunner.Options.canonical(outputDir);
                var b = HtmlKnowledgeIngestionRunner.Options.canonical(storeRoot.get());
                if (a.startsWith(b) || b.startsWith(a)) {
                    throw new IllegalArgumentException(
                            "--output-dir and --store-root must be separate, non-nested directories");
                }
            }
        }

        /** Parses CLI arguments; throws {@link IllegalArgumentException} on invalid usage. */
        public static Options parse(String[] args) {
            var outputDir = Path.of("build/reports/benchmarks");
            Path storeRoot = null;
            var rungs = List.of(Rung.values());
            var seed = ScalingDistractorGenerator.DEFAULT_SEED;
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--points" -> rungs = rungs(value(args, ++i, "--points"));
                    case "--seed" -> seed = number(value(args, ++i, "--seed"));
                    case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                    case "--store-root" -> storeRoot = Path.of(value(args, ++i, "--store-root"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            return new Options(outputDir, Optional.ofNullable(storeRoot), rungs, seed);
        }

        private static List<Rung> rungs(String value) {
            var rungs = Arrays.stream(value.split(",", -1)).map(String::strip).map(Rung::ofLabel).toList();
            for (var i = 1; i < rungs.size(); i++) {
                if (rungs.get(i).ordinal() <= rungs.get(i - 1).ordinal()) {
                    throw new IllegalArgumentException("--points must be distinct and in ladder order, was " + value);
                }
            }
            return rungs;
        }

        private static long number(String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--seed requires an integer, was '" + value + "'");
            }
        }
    }

    /**
     * Outcome of a run.
     *
     * @param report verdicts, metadata and environment
     * @param diagnosis the payload, when every point was diagnosed
     * @param outputDir the preflighted output directory
     */
    public record Outcome(CheckedEvaluationReport report, Optional<HtmlKnowledgeRecallDiagnosisReport> diagnosis,
            Path outputDir) {

        /** Returns the single JSON report: verdicts and metadata with the payload as {@code diagnosis}. */
        public String json() {
            return report.toJson(Map.of("diagnosis", diagnosis.map(HtmlKnowledgeRecallDiagnosisReport::toJson)
                    .orElse("null")));
        }

        /** Returns the Markdown report: verdicts and metadata, then the summary. */
        public String markdown() {
            return report.toMarkdown() + diagnosis.map(rendered -> "\n" + rendered.toMarkdown()).orElse("");
        }
    }

    /** CLI entrypoint. */
    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        Outcome outcome;
        try {
            outcome = run(options);
        } catch (IngestionInputException | CorpusValidationException e) {
            System.err.println("Diagnosis input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.markdown());
        try {
            var written = write(outcome);
            System.out.println("Diagnosis outputs saved to:");
            written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write diagnosis outputs", e);
        }
        if (!outcome.report().allPassed()) {
            System.err.println("Semantic diagnosis checks failed.");
            System.exit(1);
        }
    }

    /**
     * Runs the diagnosis. The temporary directory, unless {@code --store-root} was given, is deleted before returning.
     *
     * @throws IngestionInputException when the output location or the kept directory is rejected
     * @throws CorpusValidationException when a corpus or the query set violates its contract
     */
    public static Outcome run(Options options) {
        return run(options, UnaryOperator.identity());
    }

    /** {@link #run(Options)} with a test seam wrapping the Store-side recall of the capture. */
    static Outcome run(Options options, UnaryOperator<ResonanceStoreRecall> storeSeam) {
        Objects.requireNonNull(options, "options must not be null");
        HtmlKnowledgeIngestionRunner.prepareOutput(options.outputDir(), List.of(
                options.outputDir().resolve(REPORT_JSON), options.outputDir().resolve(REPORT_MARKDOWN)));
        options.storeRoot().ifPresent(HtmlKnowledgeIngestion::requireFreshRoot);

        var base = HtmlKnowledgeCorpus.bundled();
        var querySet = HtmlKnowledgeQuerySet.bundled(base);
        // The #111 curve cohort: fixed by every protocol size point, so it is the same at every ladder point.
        var cohort = HtmlKnowledgeScalingCorpus.curve(base, querySet);
        var ladder = RecallDiagnosisLadder.build(base, querySet, options.rungs(), options.seed());
        var diagnosis = new HtmlKnowledgeRecallDiagnosis(storeSeam);

        var checks = new ArrayList<Check>();
        var metadata = new TreeMap<String, String>();
        var hashes = ladder.stream().map(point -> point.point().corpus().corpusHash()).toList();
        var distinct = new HashSet<>(hashes).size() == hashes.size();
        var cohortPresent = ladder.stream().allMatch(point -> point.point().eligible().containsAll(cohort));
        checks.add(new Check("ladder.corpora", distinct && cohortPresent, (distinct ? "every ladder corpus validated"
                + " and has its own corpusHash" : "ladder corpus hashes repeat") + "; " + (cohortPresent
                ? "every relevant chunk of the " + cohort.size() + " cohort queries is present at every point"
                : "a cohort query's relevant chunk is missing at some point") + ": " + ladder.stream()
                .map(point -> point.rung().label() + " " + point.point().corpus().corpusHash().substring(0, 12))
                .collect(Collectors.joining(", "))));

        var directory = options.storeRoot().orElseGet(HtmlKnowledgeRecallDiagnosisRunner::temporaryDirectory);
        var results = new ArrayList<PointDiagnosis>();
        try {
            new RunEnvironment().describeStoreRoot("env.storeRoot", directory, metadata);
            for (var point : ladder) {
                var result = diagnosis.measure(point, querySet, cohort, directory.resolve(point.rung().label()));
                results.add(result);
                checks.addAll(result.checks());
            }
        } finally {
            if (options.storeRoot().isEmpty()) {
                HtmlKnowledgeIngestionRunner.deleteTreeQuietly(directory)
                        .ifPresent(left -> metadata.put("env.cleanupLeftovers", left));
            }
        }
        describe(options, base, querySet, cohort, ladder, metadata);

        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(),
                new RunConfiguration(options.seed(), false, 0, 0, "no timed rows: a semantic diagnosis over the"
                        + " captured Store lists; cost is reported as Store calls and candidate counts"),
                List.of(), TITLE);
        var report = new CheckedEvaluationReport(TITLE, "Diagnosis Metadata", evaluation, checks, metadata);
        var rendered = results.size() == ladder.size()
                ? Optional.of(new HtmlKnowledgeRecallDiagnosisReport(querySet, cohort, options.seed(), results))
                : Optional.<HtmlKnowledgeRecallDiagnosisReport>empty();
        return new Outcome(report, rendered, options.outputDir());
    }

    /** Writes the reports into the preflighted output directory. */
    public static List<Path> write(Outcome outcome) throws IOException {
        var outputDir = outcome.outputDir();
        return List.of(
                HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(REPORT_JSON), outcome.json()),
                HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(REPORT_MARKDOWN), outcome.markdown()));
    }

    private static void describe(Options options, HtmlKnowledgeCorpus base, HtmlKnowledgeQuerySet querySet,
            List<JudgedQuery> cohort, List<RecallDiagnosisLadder.LadderPoint> ladder, Map<String, String> metadata) {
        metadata.put("protocolVersion", HtmlKnowledgeIngestionRunner.PROTOCOL_VERSION);
        metadata.put("study", STUDY);
        metadata.put("runMode", "points " + options.rungs().stream().map(Rung::label).toList() + "; "
                + (options.storeRoot().isPresent() ? "kept point roots" : "temporary point roots (deleted)")
                + "; in-process; nothing is timed");
        metadata.put("corpus.base.version", base.version());
        metadata.put("corpus.base.hash", base.corpusHash());
        metadata.put("generator.version", ScalingDistractorGenerator.VERSION);
        metadata.put("generator.seed", Long.toString(options.seed()));
        metadata.put("query.version", querySet.version());
        metadata.put("query.hash", querySet.queryHash());
        metadata.put("query.cohort", cohort.size() + " queries of the #111 curve population: "
                + cohort.stream().map(JudgedQuery::queryId).collect(Collectors.joining(", ")));
        metadata.put("query.cohortUse", "descriptive diagnosis only, never a tuning or validation set; every merge"
                + " alternative is in-sample exploratory");
        metadata.put("k", "1, 3, 5");
        metadata.put("threshold", Double.toString(HtmlKnowledgeRecall.THRESHOLD));
        metadata.put("pools", "production pool k (the adapter's per-Signal topK(k)); expanded pool "
                + SignalHitCapture.EXPANDED_POOL + " (research-only candidate-recall change, same Store calls with a"
                + " larger topK)");
        metadata.put("codec", TokenPackingSignalCodec.describe());
        metadata.put("rules", Arrays.stream(MergeCounterfactuals.Rule.values())
                .map(rule -> rule.label() + ": " + rule.description()).collect(Collectors.joining("; ")));
        metadata.put("gold", "read only by RecallLossAttribution after the capture; the observation, the capture and"
                + " the replay see query ids and texts only");
        metadata.put("studies", "base growth " + RecallDiagnosisLadder.BASE_GROWTH.stream().map(Rung::label).toList()
                + " (no distractors); distractor growth " + RecallDiagnosisLadder.DISTRACTOR_GROWTH.stream()
                .map(Rung::label).toList() + " (complete 33-chunk base)");
        for (var point : ladder) {
            var prefix = point.rung().label() + ".corpus.";
            metadata.put(prefix + "version", point.point().corpus().version());
            metadata.put(prefix + "hash", point.point().corpus().corpusHash());
            metadata.put(prefix + "chunks", Integer.toString(point.point().corpus().chunks().size()));
            metadata.put(prefix + "baseChunks", Integer.toString(point.point().baseChunks()));
            metadata.put(prefix + "generatedChunks", Integer.toString(point.point().generatedChunks()));
        }
        new RunEnvironment().describeHost(metadata);
        metadata.put("neuron.version", System.getProperty(NEURON_VERSION_PROPERTY, "unknown"));
        metadata.put("neuron.commit", StoreMetadata.gitCommit(Path.of(".").toAbsolutePath().normalize()));
        var storeDirectory = System.getProperty(STORE_DIRECTORY_PROPERTY);
        metadata.put("store.checkoutCommit",
                StoreMetadata.gitCommit(storeDirectory == null ? null : Path.of(storeDirectory)));
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("monada-neuron-html-diagnosis-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}
