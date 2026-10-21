package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.Options;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.PhaseRun;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * CLI of the HTML evidence-trace experiment (Issue #108, protocol extension {@code l3-evidence-v1}): imports
 * {@code html-kc-v1}, and the same corpus without {@link HtmlKnowledgeCycleExperiment#REMOVED_DOCUMENT}, into two fresh
 * Store roots in one JVM ({@code seed}); reopens both from a second, separate JVM ({@code trace}), which runs every
 * {@code html-kq-v1} query text through the bounded Neuron cycle and its controls; and scores the trace against the
 * gold labels in this process. The phases receive only a labels-free workload; this orchestrator's scorer is the only
 * reader of gold labels.
 *
 * <p>Options as for {@link HtmlKnowledgeRecallRunner}. Outputs: {@code html-knowledge-evidence-trace.json} (verdicts,
 * metadata and the scored payload under {@code trace}, {@code null} when nothing was scored),
 * {@code html-knowledge-evidence-trace.md} and {@code html-kq-v1-evidence-trace.tsv} (every raw trace row). Exit
 * status: {@code 0} all checks passed, {@code 1} a semantic check failed, {@code 2} invalid usage or rejected input.
 */
public final class HtmlKnowledgeEvidenceTraceRunner {

    /** Report title. */
    public static final String TITLE = "Monada Neuron HTML Knowledge Evidence Trace Report";

    /** The protocol extension this experiment delivers. */
    public static final String EXTENSION = "l3-evidence-v1";

    private static final String USAGE = "Usage: [--output-dir <dir>] [--store-root <dir>]";
    private static final String REPORT_JSON = "html-knowledge-evidence-trace.json";
    private static final String REPORT_MARKDOWN = "html-knowledge-evidence-trace.md";

    private final Options options;
    private final HtmlKnowledgeCorpus corpus;
    private final HtmlKnowledgeQuerySet querySet;
    private final PhaseJvm phaseJvm = new PhaseJvm();

    HtmlKnowledgeEvidenceTraceRunner(Options options) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.corpus = HtmlKnowledgeCorpus.bundled();
        this.querySet = HtmlKnowledgeQuerySet.bundled(corpus);
    }

    /**
     * Outcome of a run.
     *
     * @param report verdicts, metadata and environment
     * @param trace the scored trace rendered as JSON and Markdown, when the trace phase left a snapshot
     * @param seed the seed child process
     * @param tracePhase the trace child process, when the seed phase completed
     * @param files further output file contents by file name
     * @param outputDir the preflighted output directory
     */
    public record Outcome(CheckedEvaluationReport report, Optional<HtmlKnowledgeRecallRunner.Rendered> trace,
            PhaseRun seed, Optional<PhaseRun> tracePhase, Map<String, String> files, Path outputDir) {

        public Outcome {
            files = Map.copyOf(files);
        }

        /** Returns the single JSON report: verdicts and metadata with the scored payload as {@code trace}. */
        public String json() {
            return report.toJson(Map.of("trace", trace.map(HtmlKnowledgeRecallRunner.Rendered::json).orElse("null")));
        }

        /** Returns the full Markdown report: verdicts and metadata, then the scored summary. */
        public String markdown() {
            return report.toMarkdown() + trace.map(rendered -> "\n" + rendered.markdown()).orElse("");
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
            System.err.println("Evidence trace input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.markdown());
        try {
            var written = write(outcome);
            System.out.println("Evidence trace outputs saved to:");
            written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write evidence trace outputs", e);
        }
        if (!outcome.report().allPassed()) {
            System.err.println("Semantic evidence trace checks failed.");
            System.exit(1);
        }
    }

    /**
     * Runs the experiment. The temporary work directory, and the Store roots unless {@code --store-root} was given,
     * are deleted before returning.
     *
     * @throws IngestionInputException when the output location or the kept root is rejected
     * @throws CorpusValidationException when the corpus or the query set violates its contract
     */
    public static Outcome run(Options options) {
        return new HtmlKnowledgeEvidenceTraceRunner(options).execute();
    }

    /** Writes the reports and the result files into the preflighted output directory. */
    public static List<Path> write(Outcome outcome) throws IOException {
        var outputDir = outcome.outputDir();
        var written = new ArrayList<Path>();
        written.add(HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(REPORT_JSON), outcome.json()));
        written.add(HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(REPORT_MARKDOWN), outcome.markdown()));
        for (var file : new TreeMap<>(outcome.files()).entrySet()) {
            written.add(HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(file.getKey()), file.getValue()));
        }
        return written;
    }

    private Outcome execute() {
        HtmlKnowledgeIngestionRunner.prepareOutput(options.outputDir(), outputFiles());
        options.storeRoot().ifPresent(HtmlKnowledgeIngestion::requireFreshRoot);

        var work = new WorkDirectory(temporaryDirectory());
        try {
            var root = options.storeRoot().orElse(work.path().resolve("store"));
            work.write(work.workload(), QueryText.renderTsv(querySet.queryHash(), querySet.queries().stream()
                    .map(query -> new QueryText(query.queryId(), query.text())).toList()));
            var checks = new ArrayList<Check>();
            var metadata = new TreeMap<String, String>();
            var files = new TreeMap<String, String>();

            var seed = phaseJvm.launch(HtmlKnowledgeEvidenceTracePhase.class, HtmlKnowledgeEvidenceTracePhase.SEED,
                    root, work);
            HtmlKnowledgePersistenceRunner.collect(seed, work, checks, metadata);
            Optional<PhaseRun> tracePhase = Optional.empty();
            Optional<HtmlKnowledgeRecallRunner.Rendered> rendered = Optional.empty();
            Optional<EvidenceTrace.Snapshot> snapshot = Optional.empty();
            var seeded = Files.exists(work.result(HtmlKnowledgeEvidenceTracePhase.SEED))
                    && Files.exists(HtmlKnowledgeEvidenceTracePhase.manifestFile(work,
                            HtmlKnowledgeEvidenceTracePhase.FULL))
                    && Files.exists(HtmlKnowledgeEvidenceTracePhase.manifestFile(work,
                            HtmlKnowledgeEvidenceTracePhase.REMOVED));
            if (seeded) {
                var traced = phaseJvm.launch(HtmlKnowledgeEvidenceTracePhase.class,
                        HtmlKnowledgeEvidenceTracePhase.TRACE, root, work);
                tracePhase = Optional.of(traced);
                HtmlKnowledgePersistenceRunner.collect(traced, work, checks, metadata);
                checks.add(HtmlKnowledgePersistenceRunner.restartCheck(seed, traced, work));
                snapshot = snapshot(work);
                if (snapshot.isPresent()) {
                    rendered = scoreTrace(snapshot.get(), checks);
                } else {
                    checks.add(new Check("trace.scored", false, "the trace phase left no readable snapshot (exit "
                            + traced.exitStatus() + "): " + traced.stderr()));
                }
            } else {
                checks.add(new Check("process.restart", false, "seed phase did not complete (exit "
                        + seed.exitStatus() + "; result or manifests missing); no trace phase was run: "
                        + seed.stderr()));
            }

            describe(root, seed, tracePhase, metadata);
            var evaluation = new EvaluationReport(
                    Instant.now(),
                    EnvironmentMetadata.current(),
                    new RunConfiguration(0L, false, 0, 0, HtmlKnowledgePersistenceRunner.launchedPhases(
                            HtmlKnowledgeEvidenceTracePhase.phases(),
                            Stream.of(Optional.of(seed), tracePhase).flatMap(Optional::stream).toList())
                            + "; the environment above is the orchestrator's, each phase JVM's own is in"
                            + " <phase>.jvm.*; latency is not measured by this experiment"),
                    List.of(),
                    TITLE);
            var report = new CheckedEvaluationReport(TITLE, "Evidence Trace Metadata", evaluation, checks, metadata);
            snapshot.ifPresent(value -> files.put(querySet.version() + "-evidence-trace.tsv",
                    "# raw trace-phase rows, not a verified result on their own: corpusHash " + corpus.corpusHash()
                            + ", allChecksPassed " + report.allPassed() + " (see " + REPORT_JSON + ")\n"
                            + value.toTsv()));
            return new Outcome(report, rendered, seed, tracePhase, files, options.outputDir());
        } finally {
            HtmlKnowledgeIngestionRunner.deleteTree(work.path());
        }
    }

    /**
     * Scores and renders a parsed trace snapshot, adding the passing check {@code trace.scored} and the scorer's
     * checks. A snapshot that parses but is not a
     * complete trace of the query set (a missing, duplicate or foreign row, or a field on the wrong arm) is rejected
     * by the scorer; that becomes the failed check {@code trace.scored} and an empty result, so the run still writes
     * its report, with {@code trace: null}, and exits {@code 1}.
     */
    Optional<HtmlKnowledgeRecallRunner.Rendered> scoreTrace(EvidenceTrace.Snapshot snapshot, List<Check> checks) {
        try {
            var scores = EvidenceTraceMetrics.score(querySet, corpus, snapshot);
            var report = new HtmlKnowledgeEvidenceTraceReport(scores);
            var rendered = new HtmlKnowledgeRecallRunner.Rendered(report.toJson(), report.toMarkdown());
            var rows = scores.byArm().values().stream().mapToInt(List::size).sum();
            var unscored = scores.aggregates().values().stream()
                    .mapToInt(EvidenceTraceMetrics.ArmAggregate::unscored).sum();
            checks.add(new Check("trace.scored", true, "the scorer accepted the trace snapshot: " + rows + " rows, "
                    + unscored + " unscored"));
            checks.addAll(scores.checks());
            return Optional.of(rendered);
        } catch (IllegalArgumentException e) {
            checks.add(new Check("trace.scored", false, "the trace snapshot was rejected by the scorer; nothing was"
                    + " scored: " + e.getMessage()));
            return Optional.empty();
        }
    }

    private Optional<EvidenceTrace.Snapshot> snapshot(WorkDirectory work) {
        var file = work.snapshot(HtmlKnowledgeEvidenceTracePhase.TRACE);
        if (!Files.exists(work.result(HtmlKnowledgeEvidenceTracePhase.TRACE)) || !Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(EvidenceTrace.Snapshot.parseTsv(work.read(file)));
        } catch (IllegalArgumentException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private List<Path> outputFiles() {
        var outputDir = options.outputDir();
        return List.of(outputDir.resolve(REPORT_JSON), outputDir.resolve(REPORT_MARKDOWN),
                outputDir.resolve(querySet.version() + "-evidence-trace.tsv"));
    }

    private void describe(Path root, PhaseRun seed, Optional<PhaseRun> trace, Map<String, String> metadata) {
        metadata.put("protocolVersion", HtmlKnowledgeIngestionRunner.PROTOCOL_VERSION);
        metadata.put("extensions", EXTENSION);
        metadata.put("reopenMode", "restart (seed and trace in separate JVM processes)");
        metadata.put("corpus.version", corpus.version());
        metadata.put("corpus.hash", corpus.corpusHash());
        metadata.put("corpus.chunks", Integer.toString(corpus.chunks().size()));
        var removed = HtmlKnowledgeEvidenceTracePhase.withoutDocument(corpus,
                HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT);
        metadata.put("corpus.removed.version", removed.version());
        metadata.put("corpus.removed.hash", removed.corpusHash());
        metadata.put("corpus.removed.chunks", Integer.toString(removed.chunks().size()));
        metadata.put("query.version", querySet.version());
        metadata.put("query.hash", querySet.queryHash());
        metadata.put("query.count", Integer.toString(querySet.queries().size()));
        metadata.put("query.heldOutHash", "none");
        metadata.put("query.tuning", "not tuned: no parameter was chosen on this set (t is the Store default)");
        metadata.put("seed", "none (no randomness)");
        metadata.put("k", Integer.toString(HtmlKnowledgeCycleExperiment.MEMORY_LIMIT) + " (memory-stage limit)");
        metadata.put("threshold", Double.toString(HtmlKnowledgeRecall.THRESHOLD));
        metadata.put("codec", TokenPackingSignalCodec.describe());
        metadata.put("codec.scope", "a function of the query text only; reads no query id or gold label");
        metadata.put("memoryPort", "ResonanceStoreMemoryAdapter with the L2 codec, HashedSignalDecoder and default"
                + " options, the same configuration as B2 of html-knowledge-recall");
        metadata.put("resolver", "RecalledReferenceResolver: manifest reference -> chunkId, trusted only for chunks the"
                + " trace JVM read back from the reopened root with bytes hashing to the corpus sha256; unknown or"
                + " unverified references fail closed");
        metadata.put("phase.forwardedJvmArguments", String.join(" ", phaseJvm.forwardedJvmArguments()));
        for (var run : Stream.of(Optional.of(seed), trace).flatMap(Optional::stream).toList()) {
            metadata.put("phase." + run.phase() + ".pid", Long.toString(run.pid()));
            metadata.put("phase." + run.phase() + ".exit", Integer.toString(run.exitStatus()));
            metadata.put("process." + run.phase() + ".wallNanos", Long.toString(run.nanos()));
        }
        HtmlKnowledgePersistenceRunner.describeStore(root.resolve(HtmlKnowledgeEvidenceTracePhase.FULL),
                options.storeRoot(), metadata);
        metadata.put("runMode", (options.storeRoot().isPresent() ? "kept Store root" : "default (temporary Store root)")
                + "; profile html-knowledge-protocol-v1 " + EXTENSION + " evidence trace");
        var environment = new RunEnvironment();
        environment.describeHost(metadata);
        environment.describeStoreRoot("env.storeRoot", root, metadata);
    }

    private Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("monada-neuron-html-kq-evidence-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
