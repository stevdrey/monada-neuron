package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import monada.neuron.evaluation.integration.StoreMetadata;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgePersistencePhase.Result;
import monada.neuron.evaluation.knowledge.PersistenceProbes.Snapshot;
import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * CLI of the HTML knowledge persistence experiment (Issue #106): imports {@code html-kc-v1} into a fresh Store root
 * in one JVM ({@code seed}), observes the same root from a second, separate JVM ({@code reopen}), and then runs the
 * default-codec measurement and the failure audits on copies of the root in this process.
 *
 * <p>Persistence evidence is the comparison between the two child processes, whose process ids and exit statuses are
 * reported; the Store has no {@code close}, so nothing observed inside one JVM counts as a restart.
 *
 * <p>Options: {@code --output-dir <dir>} (default {@code build/reports/benchmarks}) and {@code --store-root <dir>}
 * (keep the Store there instead of a deleted temporary root; it must be absent or empty). Exit status: {@code 0} all
 * checks passed, {@code 1} a semantic check failed, {@code 2} invalid usage or rejected input.
 */
public final class HtmlKnowledgePersistenceRunner {

    /** Report title. */
    public static final String TITLE = "Monada Neuron HTML Knowledge Persistence Report";

    /** Wall-clock bound of one child phase. */
    static final long PHASE_TIMEOUT_SECONDS = 300;

    private static final String NEURON_VERSION_PROPERTY = "monada.neuron.version";
    private static final String STORE_DIRECTORY_PROPERTY = "monada.resonance.store.dir";
    private static final int STDERR_TAIL_LINES = 20;
    private static final String USAGE = "Usage: [--output-dir <dir>] [--store-root <dir>]";

    private HtmlKnowledgePersistenceRunner() {
    }

    /**
     * Parsed options.
     *
     * @param outputDir report directory
     * @param storeRoot kept Store root, or empty for a deleted temporary root
     */
    public record Options(Path outputDir, Optional<Path> storeRoot) {

        /** Rejects a kept root that overlaps the output directory, compared in canonical form. */
        public Options {
            Objects.requireNonNull(outputDir, "outputDir must not be null");
            Objects.requireNonNull(storeRoot, "storeRoot must not be null");
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
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                    case "--store-root" -> storeRoot = Path.of(value(args, ++i, "--store-root"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            return new Options(outputDir, Optional.ofNullable(storeRoot));
        }
    }

    /**
     * One child phase as observed by the orchestrator.
     *
     * @param phase {@code seed} or {@code reopen}
     * @param pid operating-system process id of the child JVM
     * @param exitStatus its exit status, or {@code -1} when it was killed after the timeout
     * @param nanos wall time from start to exit
     * @param stderr last lines of its standard error
     */
    public record PhaseRun(String phase, long pid, int exitStatus, long nanos, String stderr) {
    }

    /**
     * Outcome of a run.
     *
     * @param report rendered verdicts, metadata and environment
     * @param seed the seed child process
     * @param reopen the reopen child process, when the seed phase produced a usable root
     * @param files output file contents by file name (manifest and snapshots)
     * @param outputDir the preflighted output directory
     */
    public record Outcome(CheckedEvaluationReport report, PhaseRun seed, Optional<PhaseRun> reopen,
            Map<String, String> files, Path outputDir) {

        public Outcome {
            files = Map.copyOf(files);
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
        } catch (IngestionInputException e) {
            System.err.println("Persistence input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.report().toMarkdown());
        try {
            var written = write(outcome);
            System.out.println("Persistence outputs saved to:");
            written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write persistence outputs", e);
        }
        if (!outcome.report().allPassed()) {
            System.err.println("Semantic persistence checks failed.");
            System.exit(1);
        }
    }

    /**
     * Runs the experiment. The temporary work directory, and the Store root unless {@code --store-root} was given,
     * are deleted before returning.
     *
     * @throws IngestionInputException when the output location or the kept root is rejected
     */
    public static Outcome run(Options options) {
        Objects.requireNonNull(options, "options must not be null");
        HtmlKnowledgeIngestionRunner.prepareOutput(options.outputDir(), outputFiles(options.outputDir()));
        options.storeRoot().ifPresent(HtmlKnowledgeIngestion::requireFreshRoot);

        var corpus = HtmlKnowledgeCorpus.bundled();
        var persistence = new HtmlKnowledgePersistence(corpus);
        var work = temporaryDirectory();
        try {
            var root = options.storeRoot().orElse(work.resolve("store"));
            var checks = new ArrayList<Check>();
            var metadata = new TreeMap<String, String>();
            var files = new TreeMap<String, String>();

            var seed = launch(HtmlKnowledgePersistencePhase.SEED, root, work);
            collect(seed, work, checks, metadata);
            Optional<PhaseRun> reopen = Optional.empty();
            if (seed.exitStatus() == 0 || seed.exitStatus() == 1) {
                var manifest = IngestionManifest.parseTsv(
                        HtmlKnowledgePersistencePhase.read(work.resolve(HtmlKnowledgePersistencePhase.MANIFEST_FILE)),
                        corpus.version(), corpus.corpusHash());
                files.put(corpus.version() + "-persistence-manifest.tsv", manifest.toTsv());
                files.put(corpus.version() + "-seed-recall.tsv", HtmlKnowledgePersistencePhase.read(
                        HtmlKnowledgePersistencePhase.snapshotFile(work, HtmlKnowledgePersistencePhase.SEED)));

                var reopened = launch(HtmlKnowledgePersistencePhase.REOPEN, root, work);
                reopen = Optional.of(reopened);
                collect(reopened, work, checks, metadata);
                checks.add(restartCheck(seed, reopened));

                var reopenSnapshot = HtmlKnowledgePersistencePhase.snapshotFile(work, HtmlKnowledgePersistencePhase.REOPEN);
                if (Files.exists(reopenSnapshot)) {
                    var snapshot = HtmlKnowledgePersistencePhase.read(reopenSnapshot);
                    files.put(corpus.version() + "-reopen-recall.tsv", snapshot);
                    var scratch = Files.createDirectories(work.resolve("audits"));
                    checks.addAll(persistence.audit(root, manifest, Snapshot.parseTsv(snapshot), scratch));
                }
            } else {
                checks.add(new Check("process.restart", false, "seed phase did not complete (exit "
                        + seed.exitStatus() + "); no reopen was attempted: " + seed.stderr()));
            }

            describe(corpus, persistence, root, options, seed, reopen, metadata);
            var evaluation = new EvaluationReport(
                    Instant.now(),
                    EnvironmentMetadata.current(),
                    new RunConfiguration(0L, false, 0, 0, "one seed and one reopen JVM; timings in metadata are"
                            + " single exploratory wall-clock samples and never part of a verdict"),
                    List.of(),
                    TITLE);
            var report = new CheckedEvaluationReport(TITLE, "Persistence Metadata", evaluation, checks, metadata);
            return new Outcome(report, seed, reopen, files, options.outputDir());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            HtmlKnowledgeIngestionRunner.deleteTree(work);
        }
    }

    /** Writes the report and the manifest and snapshot files into the preflighted output directory. */
    public static List<Path> write(Outcome outcome) throws IOException {
        var outputDir = outcome.outputDir();
        var written = new ArrayList<Path>();
        written.add(HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve("html-knowledge-persistence.json"),
                outcome.report().toJson()));
        written.add(HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve("html-knowledge-persistence.md"),
                outcome.report().toMarkdown()));
        for (var file : new TreeMap<>(outcome.files()).entrySet()) {
            written.add(HtmlKnowledgeIngestionRunner.writeFile(outputDir.resolve(file.getKey()), file.getValue()));
        }
        return written;
    }

    private static List<Path> outputFiles(Path outputDir) {
        var version = HtmlKnowledgeCorpus.VERSION;
        return List.of(outputDir.resolve("html-knowledge-persistence.json"),
                outputDir.resolve("html-knowledge-persistence.md"),
                outputDir.resolve(version + "-persistence-manifest.tsv"),
                outputDir.resolve(version + "-seed-recall.tsv"),
                outputDir.resolve(version + "-reopen-recall.tsv"));
    }

    /**
     * Runs one phase in a child JVM with this JVM's {@code java} executable and class path, so the child loads
     * exactly the classes under test.
     */
    static PhaseRun launch(String phase, Path root, Path work) {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var stdout = work.resolve(phase + ".out");
        var stderr = work.resolve(phase + ".err");
        var command = List.of(java, "-cp", System.getProperty("java.class.path"),
                HtmlKnowledgePersistencePhase.class.getName(), phase, root.toAbsolutePath().toString(),
                work.toAbsolutePath().toString());
        var start = System.nanoTime();
        try {
            var process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
                    .start();
            var exit = process.waitFor(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS) ? process.exitValue() : -1;
            if (exit == -1) {
                process.destroyForcibly().waitFor();
            }
            return new PhaseRun(phase, process.pid(), exit, System.nanoTime() - start, tail(stderr));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start the " + phase + " phase JVM", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the " + phase + " phase", e);
        }
    }

    private static void collect(PhaseRun run, Path work, List<Check> checks, Map<String, String> metadata) {
        var resultFile = HtmlKnowledgePersistencePhase.resultFile(work, run.phase());
        if (!Files.exists(resultFile)) {
            checks.add(new Check(run.phase() + ".completed", false, "the " + run.phase() + " JVM exited "
                    + run.exitStatus() + " without a result: " + run.stderr()));
            return;
        }
        var result = Result.parse(HtmlKnowledgePersistencePhase.read(resultFile));
        result.checks().forEach(check -> checks.add(
                new Check(run.phase() + "." + check.name(), check.passed(), check.detail())));
        result.metadata().forEach((key, value) -> metadata.put(run.phase() + "." + key, value));
    }

    private static Check restartCheck(PhaseRun seed, PhaseRun reopen) {
        var self = ProcessHandle.current().pid();
        var separate = seed.pid() != reopen.pid() && seed.pid() != self && reopen.pid() != self;
        var passed = separate && seed.exitStatus() == 0 && reopen.exitStatus() == 0;
        return new Check("process.restart", passed, "seed JVM pid " + seed.pid() + " exited " + seed.exitStatus()
                + "; reopen JVM pid " + reopen.pid() + " exited " + reopen.exitStatus() + "; orchestrator pid " + self
                + (separate ? "; three distinct processes" : "; processes are not distinct"));
    }

    private static void describe(HtmlKnowledgeCorpus corpus, HtmlKnowledgePersistence persistence, Path root,
            Options options, PhaseRun seed, Optional<PhaseRun> reopen, Map<String, String> metadata) {
        metadata.put("protocolVersion", HtmlKnowledgeIngestionRunner.PROTOCOL_VERSION);
        metadata.put("reopenMode", "restart (seed and reopen in separate JVM processes)");
        metadata.put("corpus.version", corpus.version());
        metadata.put("corpus.hash", corpus.corpusHash());
        metadata.put("corpus.chunks", Integer.toString(corpus.chunks().size()));
        metadata.put("codec", ChunkTextProbeCodec.describe());
        metadata.put("codec.scope", "attribution and run comparison only; a lookup codec, not valid for L2 quality");
        metadata.put("probes.count", Integer.toString(persistence.probes().size()));
        metadata.put("probes.topK", Integer.toString(HtmlKnowledgePersistence.TOP_K));
        metadata.put("probes.threshold", Double.toString(HtmlKnowledgePersistence.THRESHOLD));
        metadata.put("ordering.direct", "Store: score descending, then atom id ascending");
        metadata.put("ordering.neuron", "adapter: score descending, then query Signal index, then Store rank;"
                + " deduplicated by atom");
        metadata.put("phase.seed.pid", Long.toString(seed.pid()));
        metadata.put("phase.seed.exit", Integer.toString(seed.exitStatus()));
        metadata.put("phase.seed.nanos", Long.toString(seed.nanos()));
        reopen.ifPresent(run -> {
            metadata.put("phase.reopen.pid", Long.toString(run.pid()));
            metadata.put("phase.reopen.exit", Integer.toString(run.exitStatus()));
            metadata.put("phase.reopen.nanos", Long.toString(run.nanos()));
        });
        metadata.put("store.root", options.storeRoot().isPresent() ? root.toAbsolutePath().toString()
                : "temporary (deleted)");
        var manifest = root.resolve("manifest.json");
        if (Files.exists(manifest)) {
            StoreMetadata.manifestFields(HtmlKnowledgePersistencePhase.read(manifest))
                    .forEach((key, value) -> metadata.put("store.manifest." + key, value));
        }
        var storeVersion = MonadaMemory.class.getPackage().getImplementationVersion();
        metadata.put("store.implementationVersion", storeVersion == null ? "unknown" : storeVersion);
        metadata.put("neuron.version", System.getProperty(NEURON_VERSION_PROPERTY, "unknown"));
        metadata.put("neuron.commit", StoreMetadata.gitCommit(Path.of(".").toAbsolutePath().normalize()));
        var storeDirectory = System.getProperty(STORE_DIRECTORY_PROPERTY);
        metadata.put("store.checkoutCommit",
                StoreMetadata.gitCommit(storeDirectory == null ? null : Path.of(storeDirectory)));
    }

    private static String tail(Path file) {
        try {
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - STDERR_TAIL_LINES), lines.size()));
        } catch (IOException e) {
            return "<stderr unreadable: " + e.getMessage() + ">";
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("monada-neuron-html-kc-persistence-");
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
