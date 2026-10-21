package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Abstained;
import monada.neuron.evaluation.knowledge.GroundedAnswer.Answered;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.Options;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.PhaseRun;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;

import java.io.IOException;
import java.io.PrintStream;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CLI of the grounded-answer experiment (Issue #109, protocol extension {@code l4-answer-v1}). It imports
 * {@code html-kc-v1}, and the same corpus without {@link HtmlKnowledgeQaExperiment#REMOVED_DOCUMENT}, into two fresh
 * Store roots in one JVM ({@code seed}), answers every {@code html-kq-v1} query from a second, separate JVM
 * ({@code answer}), and scores the answers against the gold labels and the frozen human judgments in this process. The
 * phases receive only a labels-free workload; this orchestrator is the only reader of gold labels and judgments.
 *
 * <p>Options as for {@link HtmlKnowledgeRecallRunner}, or {@code --ask "<question>"}: an unscored demonstration that
 * imports the corpus into a temporary root and prints what B3 and B3c answer from the B1 and B2 hits, with each
 * citation's chunk, span, section and content hash, or {@code INSUFFICIENT_EVIDENCE}.
 *
 * <p>Outputs: {@code html-knowledge-answers.json} (verdicts, metadata and the scored payload under {@code answers},
 * {@code null} when nothing was scored), {@code html-knowledge-answers.md}, {@code html-kq-v1-answers.tsv} (every raw
 * answer row) and {@code html-l4-judgment-template.tsv} (the items still to be judged). Exit status: {@code 0} all
 * checks passed, {@code 1} a semantic check failed, {@code 2} invalid usage or rejected input.
 */
public final class HtmlKnowledgeAnswerRunner {

    /** Report title. */
    public static final String TITLE = "Monada Neuron HTML Knowledge Grounded Answer Report";

    /** The protocol extension this experiment delivers. */
    public static final String EXTENSION = "l4-answer-v1";

    /** SHA-256 of the frozen, approved {@code html-l4-judgments-v1} file. */
    static final String FROZEN_JUDGMENTS_HASH = "48bf215c5b7ef5480e41851e5397a3c6c986b9e681796266e6b1f6b78257423b";

    static final String TEMPLATE = "html-l4-judgment-template.tsv";

    private static final String USAGE = "Usage: [--output-dir <dir>] [--store-root <dir>] | --ask \"<question>\"";
    private static final String REPORT_JSON = "html-knowledge-answers.json";
    private static final String REPORT_MARKDOWN = "html-knowledge-answers.md";

    private final Options options;
    private final HtmlKnowledgeCorpus corpus;
    private final HtmlKnowledgeQuerySet querySet;
    private final AnswerJudgments judgments;
    private final String frozenHash;
    private final PhaseJvm phaseJvm = new PhaseJvm();

    HtmlKnowledgeAnswerRunner(Options options, AnswerJudgments judgments, String frozenHash) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.corpus = HtmlKnowledgeCorpus.bundled();
        this.querySet = HtmlKnowledgeQuerySet.bundled(corpus);
        this.judgments = Objects.requireNonNull(judgments, "judgments must not be null");
        this.frozenHash = Objects.requireNonNull(frozenHash, "frozenHash must not be null");
    }

    /**
     * Outcome of a run.
     *
     * @param report verdicts, metadata and environment
     * @param answers the scored answers rendered as JSON and Markdown, when the answer phase left a snapshot
     * @param seed the seed child process
     * @param answerPhase the answer child process, when the seed phase completed
     * @param files further output file contents by file name
     * @param outputDir the preflighted output directory
     */
    public record Outcome(CheckedEvaluationReport report, Optional<HtmlKnowledgeRecallRunner.Rendered> answers,
            PhaseRun seed, Optional<PhaseRun> answerPhase, Map<String, String> files, Path outputDir) {

        public Outcome {
            files = Map.copyOf(files);
        }

        /** Returns the single JSON report: verdicts and metadata with the scored payload as {@code answers}. */
        public String json() {
            return report.toJson(Map.of("answers", answers.map(HtmlKnowledgeRecallRunner.Rendered::json)
                    .orElse("null")));
        }

        /** Returns the full Markdown report: verdicts and metadata, then the scored summary. */
        public String markdown() {
            return report.toMarkdown() + answers.map(rendered -> "\n" + rendered.markdown()).orElse("");
        }
    }

    /** CLI entrypoint. */
    public static void main(String[] args) {
        if (args.length >= 1 && args[0].equals("--ask")) {
            if (args.length != 2) {
                System.err.println(USAGE);
                System.exit(2);
                return;
            }
            ask(args[1], System.out);
            return;
        }
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
            System.err.println("Answer input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.markdown());
        try {
            var written = write(outcome);
            System.out.println("Answer outputs saved to:");
            written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write answer outputs", e);
        }
        if (!outcome.report().allPassed()) {
            System.err.println("Semantic answer checks failed.");
            System.exit(1);
        }
    }

    /**
     * Runs the experiment with the bundled judgments. The temporary work directory, and the Store roots unless
     * {@code --store-root} was given, are deleted before returning.
     *
     * @throws IngestionInputException when the output location or the kept root is rejected
     * @throws CorpusValidationException when the corpus, the query set or the judgments violate their contract
     */
    public static Outcome run(Options options) {
        return new HtmlKnowledgeAnswerRunner(options, AnswerJudgments.bundled(), FROZEN_JUDGMENTS_HASH).execute();
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

    Outcome execute() {
        HtmlKnowledgeIngestionRunner.prepareOutput(options.outputDir(), outputFiles());
        options.storeRoot().ifPresent(HtmlKnowledgeIngestion::requireFreshRoot);

        var work = new WorkDirectory(temporaryDirectory("monada-neuron-html-kq-answers-"));
        try {
            var root = options.storeRoot().orElse(work.path().resolve("store"));
            work.write(work.workload(), QueryText.renderTsv(querySet.queryHash(), querySet.queries().stream()
                    .map(query -> new QueryText(query.queryId(), query.text())).toList()));
            var checks = new ArrayList<Check>();
            var metadata = new TreeMap<String, String>();
            var files = new TreeMap<String, String>();

            var seed = phaseJvm.launch(HtmlKnowledgeAnswerPhase.class, HtmlKnowledgeAnswerPhase.SEED, root, work);
            HtmlKnowledgePersistenceRunner.collect(seed, work, checks, metadata);
            Optional<PhaseRun> answerPhase = Optional.empty();
            Optional<HtmlKnowledgeRecallRunner.Rendered> rendered = Optional.empty();
            Optional<AnswerTrace.Snapshot> snapshot = Optional.empty();
            var seeded = Files.exists(work.result(HtmlKnowledgeAnswerPhase.SEED))
                    && Files.exists(HtmlKnowledgeEvidenceTracePhase.manifestFile(work, HtmlKnowledgeAnswerPhase.FULL))
                    && Files.exists(HtmlKnowledgeEvidenceTracePhase.manifestFile(work,
                            HtmlKnowledgeAnswerPhase.REMOVED));
            if (seeded) {
                var answered = phaseJvm.launch(HtmlKnowledgeAnswerPhase.class, HtmlKnowledgeAnswerPhase.ANSWER, root,
                        work);
                answerPhase = Optional.of(answered);
                HtmlKnowledgePersistenceRunner.collect(answered, work, checks, metadata);
                checks.add(HtmlKnowledgePersistenceRunner.restartCheck(seed, answered, work));
                snapshot = snapshot(work);
                if (snapshot.isPresent()) {
                    rendered = score(snapshot.get(), checks, files);
                } else {
                    checks.add(new Check("answer.scored", false, "the answer phase left no readable snapshot (exit "
                            + answered.exitStatus() + "): " + answered.stderr()));
                }
            } else {
                checks.add(new Check("process.restart", false, "seed phase did not complete (exit "
                        + seed.exitStatus() + "; result or manifests missing); no answer phase was run: "
                        + seed.stderr()));
            }
            checks.add(new Check("judgment.hashFrozen", judgments.hash().equals(frozenHash),
                    judgments.hash().equals(frozenHash)
                            ? "the judgments file hashes to its frozen value " + frozenHash
                            : "the judgments file hashes to " + judgments.hash() + ", not the frozen " + frozenHash));

            describe(root, seed, answerPhase, metadata);
            var evaluation = new EvaluationReport(
                    Instant.now(),
                    EnvironmentMetadata.current(),
                    new RunConfiguration(0L, false, 0, 0, HtmlKnowledgePersistenceRunner.launchedPhases(
                            HtmlKnowledgeAnswerPhase.phases(),
                            Stream.of(Optional.of(seed), answerPhase).flatMap(Optional::stream).toList())
                            + "; the environment above is the orchestrator's, each phase JVM's own is in"
                            + " <phase>.jvm.*; " + LatencyRow.RUN_CONFIGURATION_NOTE),
                    List.of(),
                    TITLE);
            var report = new CheckedEvaluationReport(TITLE, "Grounded Answer Metadata", evaluation, checks, metadata);
            snapshot.ifPresent(value -> files.put(querySet.version() + "-answers.tsv",
                    "# raw answer-phase rows, not a verified result on their own: corpusHash " + corpus.corpusHash()
                            + ", allChecksPassed " + report.allPassed() + " (see " + REPORT_JSON + ")\n"
                            + value.toTsv()));
            return new Outcome(report, rendered, seed, answerPhase, files, options.outputDir());
        } finally {
            HtmlKnowledgeIngestionRunner.deleteTree(work.path());
        }
    }

    /**
     * Scores a parsed snapshot. A snapshot the scorer rejects becomes the failed check {@code answer.scored} and an
     * empty result, so the run still writes its report, with {@code answers: null}, and exits {@code 1}.
     */
    Optional<HtmlKnowledgeRecallRunner.Rendered> score(AnswerTrace.Snapshot snapshot, List<Check> checks,
            Map<String, String> files) {
        try {
            var scores = AnswerMetrics.score(querySet, snapshot, judgments);
            var report = new HtmlKnowledgeAnswerReport(scores, corpus, new HtmlKnowledgeQaExperiment().components());
            checks.add(new Check("answer.scored", true, "the scorer accepted the answer snapshot: "
                    + scores.answers().size() + " answer rows"));
            checks.addAll(scores.checks());
            files.put(TEMPLATE, AnswerJudgments.template(scores.missing()));
            return Optional.of(new HtmlKnowledgeRecallRunner.Rendered(report.toJson(), report.toMarkdown()));
        } catch (IllegalArgumentException e) {
            checks.add(new Check("answer.scored", false, "the answer snapshot was rejected by the scorer; nothing was"
                    + " scored: " + e.getMessage()));
            return Optional.empty();
        }
    }

    private Optional<AnswerTrace.Snapshot> snapshot(WorkDirectory work) {
        var file = work.snapshot(HtmlKnowledgeAnswerPhase.ANSWER);
        if (!Files.exists(work.result(HtmlKnowledgeAnswerPhase.ANSWER)) || !Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(AnswerTrace.Snapshot.parseTsv(work.read(file)));
        } catch (IllegalArgumentException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private List<Path> outputFiles() {
        var outputDir = options.outputDir();
        return List.of(outputDir.resolve(REPORT_JSON), outputDir.resolve(REPORT_MARKDOWN),
                outputDir.resolve(querySet.version() + "-answers.tsv"), outputDir.resolve(TEMPLATE));
    }

    private void describe(Path root, PhaseRun seed, Optional<PhaseRun> answer, Map<String, String> metadata) {
        metadata.put("protocolVersion", HtmlKnowledgeIngestionRunner.PROTOCOL_VERSION);
        metadata.put("extensions", EXTENSION);
        metadata.put("reopenMode", "restart (seed and answer in separate JVM processes)");
        metadata.put("corpus.version", corpus.version());
        metadata.put("corpus.hash", corpus.corpusHash());
        metadata.put("corpus.chunks", Integer.toString(corpus.chunks().size()));
        var removed = HtmlKnowledgeEvidenceTracePhase.withoutDocument(corpus, HtmlKnowledgeQaExperiment.REMOVED_DOCUMENT);
        metadata.put("corpus.removed.version", removed.version());
        metadata.put("corpus.removed.hash", removed.corpusHash());
        metadata.put("corpus.removed.chunks", Integer.toString(removed.chunks().size()));
        metadata.put("query.version", querySet.version());
        metadata.put("query.hash", querySet.queryHash());
        metadata.put("query.count", Integer.toString(querySet.queries().size()));
        metadata.put("query.heldOutHash", "none");
        metadata.put("query.tuning", "B3c is in-sample (tuned): its coverage rule was chosen after inspecting B3 on"
                + " this set; B3 and the retrieval layers are not tuned");
        metadata.put("judgments.version", AnswerJudgments.VERSION);
        metadata.put("judgments.hash", judgments.hash());
        metadata.put("judgments.origin", judgments.origin());
        metadata.put("seed", "none (no randomness)");
        metadata.put("evidenceLayers", String.join(", ", AnswerTrace.LAYERS)
                + " (B2 content from the B2c control for the same atoms)");
        metadata.put("answerK", AnswerTrace.ANSWER_KS.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        metadata.put("threshold", Double.toString(HtmlKnowledgeRecall.THRESHOLD));
        metadata.put("codec", TokenPackingSignalCodec.describe());
        metadata.put("phase.forwardedJvmArguments", String.join(" ", phaseJvm.forwardedJvmArguments()));
        for (var run : Stream.of(Optional.of(seed), answer).flatMap(Optional::stream).toList()) {
            metadata.put("phase." + run.phase() + ".pid", Long.toString(run.pid()));
            metadata.put("phase." + run.phase() + ".exit", Integer.toString(run.exitStatus()));
            metadata.put("process." + run.phase() + ".wallNanos", Long.toString(run.nanos()));
        }
        HtmlKnowledgePersistenceRunner.describeStore(root.resolve(HtmlKnowledgeAnswerPhase.FULL), options.storeRoot(),
                metadata);
        metadata.put("runMode", (options.storeRoot().isPresent() ? "kept Store root" : "default (temporary Store root)")
                + "; profile html-knowledge-protocol-v1 " + EXTENSION + " grounded answers");
        var environment = new RunEnvironment();
        environment.describeHost(metadata);
        environment.describeStoreRoot("env.storeRoot", root, metadata);
    }

    /**
     * Answers one ad-hoc question, unscored: imports {@code html-kc-v1} into a temporary root, then prints what B3 and
     * B3c answer from the B1 and B2 hits at {@code answerK = 5}. Nothing is judged, and no gold label is read.
     */
    static void ask(String question, PrintStream out) {
        var corpus = HtmlKnowledgeCorpus.bundled();
        var directory = temporaryDirectory("monada-neuron-html-ask-");
        try {
            var root = directory.resolve("store");
            var imported = new HtmlKnowledgeIngestion(corpus).ingest(root);
            var memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
            var evidence = new RecalledEvidence(corpus, imported.manifest());
            out.println("Question: " + question);
            out.println("Store: temporary root with " + corpus.version() + " (" + corpus.chunks().size()
                    + " chunks), threshold " + HtmlKnowledgeRecall.THRESHOLD + "; unscored demonstration");
            try (var adapter = ResonanceStoreMemoryAdapter.open(root, new HtmlKnowledgeRecall(corpus)
                    .adapterConfig())) {
                for (var layer : AnswerTrace.LAYERS) {
                    var recall = layer.equals("B1") ? evidence.direct(memory, question)
                            : evidence.neuron(adapter, memory, question);
                    switch (recall) {
                        case RecalledEvidence.Failed failed -> out.println(layer + ": recall failed (" + failed.reason()
                                + "); no answer");
                        case RecalledEvidence.Retrieved retrieved -> {
                            out.println(layer + " evidence: " + (retrieved.chunks().isEmpty() ? "none"
                                    : retrieved.chunks().stream().map(AnswerComponent.EvidenceChunk::chunkId)
                                    .collect(Collectors.joining(", "))));
                            for (var rule : ExtractiveAnswerer.Rule.values()) {
                                var component = new ExtractiveAnswerer(rule);
                                out.println("  " + component.name() + ": " + render(corpus,
                                        component.answer(question, retrieved.chunks())));
                            }
                        }
                    }
                }
            }
        } finally {
            HtmlKnowledgeIngestionRunner.deleteTree(directory);
        }
    }

    private static String render(HtmlKnowledgeCorpus corpus, GroundedAnswer answer) {
        return switch (answer) {
            case Abstained abstained -> abstained.status() + " (" + abstained.reason() + ")";
            case Answered answered -> answered.claims().stream().map(claim -> "\"" + claim.text().replace("\n", " ")
                    + "\"" + claim.citations().stream().map(citation -> {
                        var chunk = corpus.chunk(citation.chunkId()).orElseThrow();
                        return "\n      cites " + citation.chunkId() + " [" + citation.start() + ", " + citation.end()
                                + ") of " + chunk.documentId() + ", section \"" + chunk.section() + "\", chunk sha256 "
                                + chunk.sha256();
                    }).collect(Collectors.joining())).collect(Collectors.joining("\n    "));
        };
    }

    private static Path temporaryDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
