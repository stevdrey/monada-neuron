package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.integration.StoreMetadata;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeScaling.PointResult;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeScaling.Sampling;
import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.memory.ResonanceMemoryPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * CLI of the L6 scaling profile (Issue #111, protocol extension {@code scaling-v1}): builds the scaling family
 * {@code html-kc-v1-scale-<N>} for N = 25, 100 and 500, imports each point into its own fresh Store root and measures
 * every pipeline phase separately with its semantic validity checked outside the timed window
 * ({@link HtmlKnowledgeScaling}). Latency is never a verdict: the exit status reflects semantic checks only, and a
 * regression is a prompt for a separately scoped follow-up issue, written by hand from the report's triage template.
 *
 * <p>Options: {@code --quick} (fewer iterations; every size point still runs), {@code --sizes 25,100,500} (an ascending
 * subset of the protocol's points), {@code --seed <n>} (generator seed, default
 * {@value ScalingDistractorGenerator#DEFAULT_SEED}), {@code --output-dir <dir>} (default
 * {@code build/reports/benchmarks}) and {@code --store-root <dir>} (keep the point roots there instead of a deleted
 * temporary directory; it must be absent or empty). Outputs: {@code html-knowledge-scaling.json} (verdicts, metadata,
 * the scored payload under {@code scaling} and every measured row) and {@code html-knowledge-scaling.md}. Exit status:
 * {@code 0} all checks passed, {@code 1} a semantic check failed, {@code 2} invalid usage or rejected input.
 */
public final class HtmlKnowledgeScalingRunner {

    /** Report title. */
    public static final String TITLE = "Monada Neuron HTML Knowledge Scaling Report";

    /** The protocol extension this runner delivers. */
    static final String EXTENSION = "scaling-v1";

    static final String REPORT_JSON = "html-knowledge-scaling.json";
    static final String REPORT_MARKDOWN = "html-knowledge-scaling.md";

    private static final String NEURON_VERSION_PROPERTY = "monada.neuron.version";
    private static final String STORE_DIRECTORY_PROPERTY = "monada.resonance.store.dir";
    private static final String USAGE = "Usage: [--quick] [--sizes 25,100,500] [--seed <n>] [--output-dir <dir>]"
            + " [--store-root <dir>]";

    private HtmlKnowledgeScalingRunner() {
    }

    /**
     * Parsed options.
     *
     * @param outputDir report directory
     * @param storeRoot kept directory of the point roots, or empty for a deleted temporary directory
     * @param quick whether to use {@link Sampling#QUICK}
     * @param sizes the size points, ascending
     * @param seed the generator seed
     */
    public record Options(Path outputDir, Optional<Path> storeRoot, boolean quick, List<Integer> sizes, long seed) {

        public Options {
            Objects.requireNonNull(outputDir, "outputDir must not be null");
            Objects.requireNonNull(storeRoot, "storeRoot must not be null");
            sizes = List.copyOf(sizes);
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
            var quick = false;
            var sizes = HtmlKnowledgeScalingCorpus.SIZES;
            var seed = ScalingDistractorGenerator.DEFAULT_SEED;
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--quick" -> quick = true;
                    case "--sizes" -> sizes = sizes(value(args, ++i, "--sizes"));
                    case "--seed" -> seed = number(value(args, ++i, "--seed"), "--seed");
                    case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                    case "--store-root" -> storeRoot = Path.of(value(args, ++i, "--store-root"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            return new Options(outputDir, Optional.ofNullable(storeRoot), quick, sizes, seed);
        }

        private static List<Integer> sizes(String value) {
            var sizes = Arrays.stream(value.split(",", -1)).map(String::strip).map(Options::size).toList();
            for (var i = 0; i < sizes.size(); i++) {
                if (!HtmlKnowledgeScalingCorpus.SIZES.contains(sizes.get(i))
                        || i > 0 && sizes.get(i) <= sizes.get(i - 1)) {
                    throw new IllegalArgumentException("--sizes must be an ascending subset of "
                            + HtmlKnowledgeScalingCorpus.SIZES + ", was " + value);
                }
            }
            return sizes;
        }

        /** Parses one size as an {@code int}, so an out-of-range value is rejected instead of narrowed. */
        private static int size(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--sizes requires integers, was '" + value + "'");
            }
        }

        private static long number(String value, String option) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " requires an integer, was '" + value + "'");
            }
        }
    }

    /**
     * Outcome of a run.
     *
     * @param report verdicts, metadata, environment and every measured row
     * @param scaling the scored payload, when every point was measured
     * @param outputDir the preflighted output directory
     */
    public record Outcome(CheckedEvaluationReport report, Optional<HtmlKnowledgeScalingReport> scaling,
            Path outputDir) {

        /** Returns the single JSON report: verdicts and metadata with the scored payload as {@code scaling}. */
        public String json() {
            return report.toJson(Map.of("scaling", scaling.map(HtmlKnowledgeScalingReport::toJson).orElse("null")));
        }

        /** Returns the Markdown report: the scored summary and the triage template, then verdicts and rows. */
        public String markdown() {
            return report.toMarkdown() + scaling.map(rendered -> "\n" + rendered.toMarkdown()).orElse("");
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
            System.err.println("Scaling input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.markdown());
        try {
            var written = write(outcome);
            System.out.println("Scaling outputs saved to:");
            written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write scaling outputs", e);
        }
        if (!outcome.report().allPassed()) {
            System.err.println("Semantic scaling checks failed.");
            System.exit(1);
        }
    }

    /**
     * Runs the profile. The temporary directory, unless {@code --store-root} was given, is deleted before returning.
     *
     * @throws IngestionInputException when the output location or the kept directory is rejected
     * @throws CorpusValidationException when a corpus or the query set violates its contract
     */
    public static Outcome run(Options options) {
        return run(options, UnaryOperator.identity(), UnaryOperator.identity());
    }

    /**
     * {@link #run(Options)} with test seams: decorators of the adapter used inside timed windows only and of the adapter
     * of the verified cycles only (see {@link HtmlKnowledgeScaling}).
     */
    static Outcome run(Options options, UnaryOperator<ResonanceMemoryPort> timedPort,
            UnaryOperator<ResonanceMemoryPort> verifiedCyclePort) {
        Objects.requireNonNull(options, "options must not be null");
        HtmlKnowledgeIngestionRunner.prepareOutput(options.outputDir(), List.of(
                options.outputDir().resolve(REPORT_JSON), options.outputDir().resolve(REPORT_MARKDOWN)));
        options.storeRoot().ifPresent(HtmlKnowledgeIngestion::requireFreshRoot);

        var base = HtmlKnowledgeCorpus.bundled();
        var querySet = HtmlKnowledgeQuerySet.bundled(base);
        var family = HtmlKnowledgeScalingCorpus.of(base, querySet, options.sizes(), options.seed());
        var sampling = options.quick() ? Sampling.QUICK : Sampling.FULL;
        var scaling = new HtmlKnowledgeScaling(sampling, timedPort, verifiedCyclePort);

        var checks = new ArrayList<Check>();
        var metadata = new TreeMap<String, String>();
        var hashes = family.points().stream().map(point -> point.corpus().corpusHash()).toList();
        var distinct = new HashSet<>(hashes).size() == hashes.size() && !hashes.contains(base.corpusHash());
        checks.add(new Check("family.corpora", distinct, (distinct ? "every point corpus validated against its bounds"
                + " and invariants and has its own corpusHash: " : "point corpus hashes repeat: ") + family.points()
                .stream().map(point -> point.corpus().version() + " " + point.corpus().corpusHash().substring(0, 12))
                .collect(Collectors.joining(", "))));

        var directory = options.storeRoot().orElseGet(HtmlKnowledgeScalingRunner::temporaryDirectory);
        var results = new ArrayList<PointResult>();
        try {
            new RunEnvironment().describeStoreRoot("env.storeRoot", directory, metadata);
            for (var point : family.points()) {
                var pointDirectory = directory.resolve("scale-" + point.size());
                var result = scaling.measure(family, point, pointDirectory);
                results.add(result);
                checks.addAll(result.checks());
                metadata.putAll(result.metadata());
                storeManifest(pointDirectory.resolve("root"), "n" + point.size() + ".store.manifest.", metadata);
            }
        } finally {
            if (options.storeRoot().isEmpty()) {
                HtmlKnowledgeIngestionRunner.deleteTreeQuietly(directory)
                        .ifPresent(left -> metadata.put("env.cleanupLeftovers", left));
            }
        }
        describe(options, sampling, family, metadata);

        var rows = results.stream().flatMap(result -> result.rows().stream()).toList();
        var evaluation = new EvaluationReport(Instant.now(), EnvironmentMetadata.current(),
                new RunConfiguration(options.seed(), options.quick(), sampling.warmups(), sampling.iterations(),
                        "warm rows: one operation per query of the fixed curve cohort, the same queries at every point"
                                + " (operationsPerIteration = cohort size); ingestion:"
                                + " one operation per chunk; setup and first-recall rows: one operation, "
                                + sampling.coldIterations() + " samples on fresh state; exploratory, never a verdict"),
                rows, TITLE);
        var report = new CheckedEvaluationReport(TITLE, "Scaling Metadata", evaluation, checks, metadata);
        var rendered = results.size() == family.points().size()
                ? Optional.of(new HtmlKnowledgeScalingReport(family, results, sampling))
                : Optional.<HtmlKnowledgeScalingReport>empty();
        return new Outcome(report, rendered, options.outputDir());
    }

    /** Writes the reports into the preflighted output directory. */
    public static List<Path> write(Outcome outcome) throws IOException {
        var outputDir = outcome.outputDir();
        return List.of(
                HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(REPORT_JSON), outcome.json()),
                HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(REPORT_MARKDOWN), outcome.markdown()));
    }

    private static void describe(Options options, Sampling sampling, HtmlKnowledgeScalingCorpus.Family family,
            Map<String, String> metadata) {
        metadata.put("protocolVersion", HtmlKnowledgeIngestionRunner.PROTOCOL_VERSION);
        metadata.put("extensions", EXTENSION);
        metadata.put("runMode", (options.quick() ? "quick" : "full") + "; sizes " + options.sizes() + "; "
                + (options.storeRoot().isPresent() ? "kept point roots" : "temporary point roots (deleted)")
                + "; in-process, cold = freshly opened handle in a warm JVM");
        metadata.put("sampling", "warm rows " + sampling.warmups() + " warm-up + " + sampling.iterations()
                + " measured; setup, ingestion and first-recall rows " + sampling.coldIterations()
                + " measured; ingestion warm-up " + sampling.ingestWarmups());
        metadata.put("measurementMethod", HtmlKnowledgeScaling.MEASUREMENT_METHOD);
        metadata.put("corpus.base.version", family.base().version());
        metadata.put("corpus.base.hash", family.base().corpusHash());
        metadata.put("corpus.chunkingPolicy", CorpusChunker.POLICY);
        metadata.put("generator.version", ScalingDistractorGenerator.VERSION);
        metadata.put("generator.seed", Long.toString(family.seed()));
        metadata.put("generator.source", ScalingDistractorGenerator.source(family.seed()));
        metadata.put("query.version", family.querySet().version());
        metadata.put("query.hash", family.querySet().queryHash());
        metadata.put("query.curvePopulation", family.curve().size() + " queries eligible at every point");
        metadata.put("query.excludedFromCurve", family.excludedFromCurve().isEmpty() ? "none"
                : String.join(", ", family.excludedFromCurve()));
        metadata.put("k", HtmlKnowledgeScaling.KS.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        metadata.put("threshold", Double.toString(HtmlKnowledgeRecall.THRESHOLD));
        metadata.put("reopenMode", "fresh-handle (in-process: each phase reopens the point root through a new handle in"
                + " this JVM; restart persistence is the evidence of #106 and #107)");
        metadata.put("codec", TokenPackingSignalCodec.describe());
        metadata.put("codec.scope", "L2 quality and B2 cost: a function of the query text only; reads no query id or gold"
                + " label");
        metadata.put("query.tuning", "none: B3 has no tuned parameter and the threshold is the Store default; the"
                + " generator templates were reviewed for irrelevance, not tuned on results");
        metadata.put("cycle", "MEMORY_RECALL -> REASONING -> EVALUATION, memory-stage and reasoning limit k, budget "
                + HtmlKnowledgeCycleExperiment.BUDGET + ", trace key " + HtmlKnowledgeCycleExperiment.TRACE_KEY);
        var answerer = new ExtractiveAnswerer(ExtractiveAnswerer.Rule.B3);
        metadata.put("answer.component", answerer.name() + ": " + answerer.description());
        metadata.put("answer.evidenceLayers", "B1, B2 (answerK = k); timed rows bind B1");
        metadata.put("answer.judgments", "unavailable at scale points: html-l4-judgments-v1 is keyed to html-kc-v1"
                + " answers; only mechanical counts are reported");
        metadata.put("answer.keyClaimCounts", "unavailable (no matcher-only or final key-claim matching and no reviewer"
                + " overrides at scale points; every answer row carries keyClaimEdges null)");
        metadata.put("l3.traceKey", HtmlKnowledgeCycleExperiment.TRACE_KEY);
        metadata.put("l3.memoryStageLimit", "k (1, 3, 5)");
        metadata.put("footprint.heapRetainedBytes", "unavailable (not measured: no heap-dump or retained-size"
                + " probe in this harness)");
        for (var point : family.points()) {
            var prefix = "n" + point.size() + ".corpus.";
            metadata.put(prefix + "version", point.corpus().version());
            metadata.put(prefix + "hash", point.corpus().corpusHash());
            metadata.put(prefix + "chunks", Integer.toString(point.corpus().chunks().size()));
            metadata.put(prefix + "documents", Integer.toString(point.corpus().documents().size()));
            metadata.put(prefix + "baseChunks", Integer.toString(point.baseChunks()));
            metadata.put(prefix + "generatedChunks", Integer.toString(point.generatedChunks()));
            metadata.put(prefix + "bounds", point.corpus().bounds().describe());
        }
        new RunEnvironment().describeHost(metadata);
        metadata.put("neuron.version", System.getProperty(NEURON_VERSION_PROPERTY, "unknown"));
        metadata.put("neuron.commit", StoreMetadata.gitCommit(Path.of(".").toAbsolutePath().normalize()));
        var storeDirectory = System.getProperty(STORE_DIRECTORY_PROPERTY);
        metadata.put("store.checkoutCommit",
                StoreMetadata.gitCommit(storeDirectory == null ? null : Path.of(storeDirectory)));
    }

    private static void storeManifest(Path root, String prefix, Map<String, String> metadata) {
        var manifest = root.resolve("manifest.json");
        try {
            if (Files.exists(manifest)) {
                StoreMetadata.manifestFields(Files.readString(manifest, StandardCharsets.UTF_8))
                        .forEach((key, value) -> metadata.put(prefix + key, value));
            } else {
                metadata.put(prefix + "file", "unavailable (no manifest.json in the root)");
            }
        } catch (IOException e) {
            metadata.put(prefix + "file", "unavailable (" + e + ")");
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("monada-neuron-html-scaling-");
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
