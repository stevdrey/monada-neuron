package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.WorkDirectory;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.Options;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistenceRunner.PhaseRun;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
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
 * CLI of the judged HTML recall experiment (Issue #107, protocol layers L1 and L2): imports {@code html-kc-v1} into a
 * fresh Store root and runs the {@code html-kq-v1} query texts through B0, B1, B2 and B2c in one JVM ({@code seed}),
 * reruns them against the same root from a second, separate JVM ({@code reopen}), and scores the seed results against
 * the gold labels in this process. The phases receive only a labels-free workload (query ids and texts); this
 * orchestrator's scorer is the only reader of gold labels.
 *
 * <p>Options: {@code --output-dir <dir>} (default {@code build/reports/benchmarks}) and {@code --store-root <dir>}
 * (keep the Store there instead of a deleted temporary root; it must be absent or empty). Outputs:
 * {@code html-knowledge-recall.json} (one object: verdicts, run identity and metadata, and the scored payload under
 * {@code recall}, which is {@code null} when nothing was scored), {@code html-knowledge-recall.md} (the same, readable)
 * and {@code html-kq-v1-recall.tsv} (every raw result of the seed phase, headed by the run's hashes and verdict).
 * Exit status: {@code 0} all checks passed, {@code 1} a semantic check failed, {@code 2} invalid usage or rejected
 * input.
 */
public final class HtmlKnowledgeRecallRunner {

    /** Report title. */
    public static final String TITLE = "Monada Neuron HTML Knowledge Judged Recall Report";

    private static final String USAGE = "Usage: [--output-dir <dir>] [--store-root <dir>]";
    private static final String REPORT_JSON = "html-knowledge-recall.json";
    private static final String REPORT_MARKDOWN = "html-knowledge-recall.md";

    private final Options options;
    private final HtmlKnowledgeCorpus corpus;
    private final HtmlKnowledgeQuerySet querySet;
    private final PhaseJvm phaseJvm = new PhaseJvm();

    HtmlKnowledgeRecallRunner(Options options) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.corpus = HtmlKnowledgeCorpus.bundled();
        this.querySet = HtmlKnowledgeQuerySet.bundled(corpus);
    }

    /**
     * Outcome of a run.
     *
     * @param report verdicts, metadata and environment
     * @param recall the scored results rendered as JSON and Markdown, when the seed phase left a snapshot
     * @param seed the seed child process
     * @param reopen the reopen child process, when the seed phase produced a usable root
     * @param files further output file contents by file name
     * @param outputDir the preflighted output directory
     */
    public record Outcome(CheckedEvaluationReport report, Optional<Rendered> recall, PhaseRun seed,
            Optional<PhaseRun> reopen, Optional<PhaseRun> replica, Map<String, String> files, Path outputDir) {

        public Outcome {
            files = Map.copyOf(files);
        }

        /** Returns the single JSON report: verdicts and metadata with the scored payload as {@code recall}. */
        public String json() {
            return report.toJson(Map.of("recall", recall.map(Rendered::json).orElse("null")));
        }

        /** Returns the full Markdown report: verdicts and metadata, then the scored summary. */
        public String markdown() {
            return report.toMarkdown() + recall.map(rendered -> "\n" + rendered.markdown()).orElse("");
        }
    }

    /** The scored results as JSON and Markdown. */
    public record Rendered(String json, String markdown) {
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
            System.err.println("Recall input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.markdown());
        try {
            var written = write(outcome);
            System.out.println("Recall outputs saved to:");
            written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write recall outputs", e);
        }
        if (!outcome.report().allPassed()) {
            System.err.println("Semantic recall checks failed.");
            System.exit(1);
        }
    }

    /**
     * Runs the experiment. The temporary work directory, and the Store root unless {@code --store-root} was given,
     * are deleted before returning.
     *
     * @throws IngestionInputException when the output location or the kept root is rejected
     * @throws CorpusValidationException when the corpus or the query set violates its contract
     */
    public static Outcome run(Options options) {
        return new HtmlKnowledgeRecallRunner(options).execute();
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
            work.write(work.workload(), HtmlKnowledgeRecall.QueryText.renderTsv(querySet.queryHash(),
                    querySet.queries().stream()
                            .map(query -> new HtmlKnowledgeRecall.QueryText(query.queryId(), query.text())).toList()));
            var checks = new ArrayList<Check>();
            var metadata = new TreeMap<String, String>();
            var files = new TreeMap<String, String>();

            var seed = phaseJvm.launch(HtmlKnowledgeRecallPhase.class, HtmlKnowledgeRecallPhase.SEED, root, work);
            HtmlKnowledgePersistenceRunner.collect(seed, work, checks, metadata);
            var seedSnapshot = snapshot(work, HtmlKnowledgeRecallPhase.SEED);
            Optional<PhaseRun> reopen = Optional.empty();
            Optional<PhaseRun> replica = Optional.empty();
            Optional<Path> replicaRoot = Optional.empty();
            Optional<Rendered> recall = Optional.empty();
            if (seedSnapshot.isPresent() && Files.exists(work.manifest())) {
                var reopened = phaseJvm.launch(HtmlKnowledgeRecallPhase.class, HtmlKnowledgeRecallPhase.REOPEN,
                        root, work);
                reopen = Optional.of(reopened);
                HtmlKnowledgePersistenceRunner.collect(reopened, work, checks, metadata);
                checks.add(HtmlKnowledgePersistenceRunner.restartCheck(seed, reopened, work));
                checks.add(restartEqual(seedSnapshot.get(), snapshot(work, HtmlKnowledgeRecallPhase.REOPEN)));

                // A fresh root of its own beside the seed root, in the same parent and so on the same storage. Fresh-root
                // determinism is a semantic run condition, so a root that cannot be created fails that check instead of
                // aborting the run without a report.
                try {
                    replicaRoot = Optional.of(createReplicaRoot(root));
                } catch (IOException | RuntimeException e) {
                    checks.add(new Check("determinism.freshRootEqual", false, "the replica root could not be created"
                            + " beside " + root + " (" + e + "); no fresh-root replica was run"));
                }
                if (replicaRoot.isPresent()) {
                    var replicated = phaseJvm.launch(HtmlKnowledgeRecallPhase.class, HtmlKnowledgeRecallPhase.REPLICA,
                            replicaRoot.get(), work);
                    replica = Optional.of(replicated);
                    HtmlKnowledgePersistenceRunner.collect(replicated, work, checks, metadata);
                    checks.add(freshRootEqual(seed, replicated, seedSnapshot.get(),
                            snapshot(work, HtmlKnowledgeRecallPhase.REPLICA)));
                }

                var scores = RecallMetrics.score(querySet, seedSnapshot.get());
                var rendered = new HtmlKnowledgeRecallReport(querySet, scores);
                recall = Optional.of(new Rendered(rendered.toJson(), rendered.toMarkdown()));
            } else {
                checks.add(new Check("process.restart", false, "seed phase did not complete (exit "
                        + seed.exitStatus() + "; snapshot or manifest missing or unreadable); nothing was scored and"
                        + " no reopen or replica was attempted: " + seed.stderr()));
            }

            describe(root, replicaRoot, seed, reopen, replica, metadata);
            // The replica root is deleted before the report is built, so a leftover is reported, never thrown.
            replicaRoot.ifPresent(path -> deleteQuietly(path, metadata));
            var evaluation = new EvaluationReport(
                    Instant.now(),
                    EnvironmentMetadata.current(),
                    new RunConfiguration(0L, false, 0, 0, HtmlKnowledgePersistenceRunner.launchedPhases(
                            List.of(HtmlKnowledgeRecallPhase.SEED, HtmlKnowledgeRecallPhase.REOPEN,
                                    HtmlKnowledgeRecallPhase.REPLICA),
                            Stream.of(Optional.of(seed), reopen, replica).flatMap(Optional::stream).toList())
                            + "; the environment above is the orchestrator's, each phase JVM's own is in <phase>.jvm.*; "
                            + LatencyRow.RUN_CONFIGURATION_NOTE),
                    List.of(),
                    TITLE);
            var report = new CheckedEvaluationReport(TITLE, "Recall Metadata", evaluation, checks, metadata);
            seedSnapshot.ifPresent(snapshot -> files.put(querySet.version() + "-recall.tsv",
                    "# raw seed-phase rows, not a verified result on their own: corpusHash " + corpus.corpusHash()
                            + ", allChecksPassed " + report.allPassed() + " (see " + REPORT_JSON + ")\n"
                            + snapshot.toTsv()));
            return new Outcome(report, recall, seed, reopen, replica, files, options.outputDir());
        } finally {
            HtmlKnowledgeIngestionRunner.deleteTree(work.path());
        }
    }

    /**
     * Requires a second, independent import into its own fresh root (the replica JVM) to reproduce every seed result.
     * Rows hold chunk ids mapped through each root's own manifest, so the roots' Store-owned atom ids are never
     * compared, as the protocol's cross-root rule requires.
     */
    private Check freshRootEqual(PhaseRun seed, PhaseRun replica, Snapshot seedSnapshot, Optional<Snapshot> replicated) {
        if (replicated.isEmpty() || replica.exitStatus() != 0 && replica.exitStatus() != 1) {
            return new Check("determinism.freshRootEqual", false, "the replica JVM (pid " + replica.pid() + ", exit "
                    + replica.exitStatus() + ") left no readable snapshot: " + replica.stderr());
        }
        var separate = replica.pid() != seed.pid() && replica.pid() != ProcessHandle.current().pid();
        var equal = seedSnapshot.equals(replicated.get());
        return new Check("determinism.freshRootEqual", separate && equal, (equal
                ? "a second import into a fresh root (replica JVM pid " + replica.pid() + ") reproduced all "
                        + seedSnapshot.rows().size() + " rows: mapped chunk ids, order and bit-exact scores"
                : "the replica differs from the seed at row " + HtmlKnowledgeRecall.firstDifference(seedSnapshot.rows(),
                        replicated.get().rows()))
                + (separate ? "" : "; the replica did not run in its own process"));
    }

    /** Requires the reopen JVM to reproduce every seed result: mapped chunk ids, order and bit-exact scores. */
    private Check restartEqual(Snapshot seed, Optional<Snapshot> reopened) {
        if (reopened.isEmpty()) {
            return new Check("determinism.restartEqual", false, "the reopen phase left no readable snapshot");
        }
        var rows = reopened.get().rows();
        var equal = seed.equals(reopened.get());
        var detail = equal
                ? "all " + rows.size() + " results of B0, B1, B2 and B2c at limits 1, 3 and 5 are identical in the"
                        + " reopen JVM: chunk ids through each phase's manifest, order and bit-exact scores"
                : "first difference at row " + HtmlKnowledgeRecall.firstDifference(seed.rows(), rows) + " (seed "
                        + seed.rows().size() + " rows, reopen " + rows.size() + " rows)";
        return new Check("determinism.restartEqual", equal, detail);
    }

    /** Returns the snapshot of {@code phase} when its result and snapshot exist and the snapshot parses. */
    private Optional<Snapshot> snapshot(WorkDirectory work, String phase) {
        if (!Files.exists(work.result(phase)) || !Files.exists(work.snapshot(phase))) {
            return Optional.empty();
        }
        try {
            return Optional.of(Snapshot.parseTsv(work.read(work.snapshot(phase))));
        } catch (IllegalArgumentException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private List<Path> outputFiles() {
        var outputDir = options.outputDir();
        return List.of(outputDir.resolve(REPORT_JSON), outputDir.resolve(REPORT_MARKDOWN),
                outputDir.resolve(querySet.version() + "-recall.tsv"),
                // Written by an earlier version of this runner; removed so no stale scores are left behind.
                outputDir.resolve(querySet.version() + "-recall-results.json"));
    }

    private void describe(Path root, Optional<Path> replicaRoot, PhaseRun seed, Optional<PhaseRun> reopen,
            Optional<PhaseRun> replica, Map<String, String> metadata) {
        metadata.put("protocolVersion", HtmlKnowledgeIngestionRunner.PROTOCOL_VERSION);
        metadata.put("extensions", "none (L1 and L2 only)");
        metadata.put("reopenMode", "restart (seed and reopen in separate JVM processes)");
        metadata.put("corpus.version", corpus.version());
        metadata.put("corpus.hash", corpus.corpusHash());
        metadata.put("corpus.chunks", Integer.toString(corpus.chunks().size()));
        metadata.put("query.version", querySet.version());
        metadata.put("query.hash", querySet.queryHash());
        metadata.put("query.count", Integer.toString(querySet.queries().size()));
        metadata.put("query.heldOutHash", "none");
        metadata.put("query.tuning", "not tuned: no parameter was chosen on this set (t is the Store default)");
        metadata.put("seed", "none (no randomness)");
        metadata.put("k", "1, 3, 5 (prefixes of one limit-5 list per system; a limit-6 lookahead only flags a tie"
                + " straddling the k = 5 cutoff)");
        metadata.put("threshold", Double.toString(HtmlKnowledgeRecall.THRESHOLD));
        metadata.put("baseline.B0", "exact-token overlap over the normalized corpus chunks; score = distinct query"
                + " tokens contained; score 0 dropped; score descending, then chunkId");
        metadata.put("baseline.B1", "MonadaMemory.resonate(text).topK(limit).threshold(t), defaults options");
        metadata.put("baseline.B2", "ResonanceStoreMemoryAdapter.recall(codec.querySignals(text), limit) with"
                + " HashedSignalDecoder and default options");
        metadata.put("baseline.B2c", "codec query texts sent directly to the Store with limit 5 and t, merged by the"
                + " adapter rule");
        metadata.put("codec", TokenPackingSignalCodec.describe());
        metadata.put("codec.scope", "L2 quality: a function of the query text only; reads no query id or gold label");
        metadata.put("ordering.direct", "Store: score descending, then atom id ascending");
        metadata.put("ordering.neuron", "adapter: score descending, then query Signal index, then Store rank;"
                + " deduplicated by atom");
        metadata.put("phase.forwardedJvmArguments", String.join(" ", phaseJvm.forwardedJvmArguments()));
        var phases = new ArrayList<PhaseRun>();
        phases.add(seed);
        reopen.ifPresent(phases::add);
        replica.ifPresent(phases::add);
        for (var run : phases) {
            var prefix = "phase." + run.phase();
            metadata.put(prefix + ".pid", Long.toString(run.pid()));
            metadata.put(prefix + ".exit", Integer.toString(run.exitStatus()));
        }
        for (var run : phases) {
            // Process bookkeeping, not a latency row: one child JVM per phase, from start to exit, including startup.
            metadata.put("process." + run.phase() + ".wallNanos", Long.toString(run.nanos()));
        }
        HtmlKnowledgePersistenceRunner.describeStore(root, options.storeRoot(), metadata);
        metadata.put("runMode", (options.storeRoot().isPresent() ? "kept Store root" : "default (temporary Store root)")
                + "; profile html-knowledge-protocol-v1 L1/L2 judged recall");
        var environment = new RunEnvironment();
        environment.describeHost(metadata);
        environment.describeStoreRoot("env.storeRoot", root, metadata);
        replicaRoot.ifPresent(path -> {
            environment.describeStoreRoot("env.replicaRoot", path, metadata);
            metadata.put("env.replicaRoot.sameFileStoreAsStoreRoot",
                    environment.sameFileStore(root, path).map(String::valueOf).orElse("unknown"));
        });
    }

    /** Creates an empty replica root beside {@code root}; the import accepts an empty directory as a fresh root. */
    private Path createReplicaRoot(Path root) throws IOException {
        var parent = root.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, ".replica-store-");
    }

    /** Deletes the replica root; a failure is recorded as {@code cleanup.leftover} and never thrown. */
    private void deleteQuietly(Path path, Map<String, String> metadata) {
        metadata.put("cleanup.leftover", HtmlKnowledgeIngestionRunner.deleteTreeQuietly(path).orElse("none"));
    }

    private Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("monada-neuron-html-kq-recall-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
