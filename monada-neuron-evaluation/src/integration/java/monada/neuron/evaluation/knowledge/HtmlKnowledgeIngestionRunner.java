package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.integration.StoreMetadata;
import monada.neuron.evaluation.metrics.CheckedEvaluationReport;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.evaluation.metrics.EvaluationReport.RunConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CLI of the HTML knowledge corpus ingestion experiment (Issue #105): validates {@code html-kc-v1}, imports it into
 * a fresh embedded Store root through {@code MonadaMemory.remember}, reads every chunk back and writes the report,
 * the chunk-label file and the {@code chunkId → atomId → opaque reference} manifest.
 *
 * <p>Options: {@code --output-dir <dir>} (default {@code build/reports/benchmarks}), {@code --store-root <dir>}
 * (keep the Store there instead of a deleted temporary root; it must be absent or empty), and the opt-in external
 * snapshot {@code --mdn-snapshot <index.md> --mdn-revision <commit> --mdn-sha256 <hex>} with an optional
 * {@code --mdn-store-root <dir>}. Exit status: {@code 0} all checks passed, {@code 1} a semantic check failed,
 * {@code 2} invalid usage or rejected input (including a missing requested snapshot or a duplicate import).
 */
public final class HtmlKnowledgeIngestionRunner {

    /** Report title. */
    public static final String TITLE = "Monada Neuron HTML Knowledge Ingestion Report";

    /** Core protocol version this experiment implements part of. */
    public static final String PROTOCOL_VERSION = "html-knowledge-protocol-v1";

    /** Every corpus version whose label and manifest files this runner can write. */
    private static final List<String> KNOWN_CORPUS_VERSIONS =
            List.of(HtmlKnowledgeCorpus.VERSION, MdnSnapshotSource.VERSION);

    private static final String NEURON_VERSION_PROPERTY = "monada.neuron.version";
    private static final String STORE_DIRECTORY_PROPERTY = "monada.resonance.store.dir";
    private static final String USAGE = "Usage: [--output-dir <dir>] [--store-root <dir>]"
            + " [--mdn-snapshot <index.md> --mdn-revision <commit> --mdn-sha256 <hex> [--mdn-store-root <dir>]]";

    private HtmlKnowledgeIngestionRunner() {
    }

    /**
     * Parsed options.
     *
     * @param outputDir report directory
     * @param storeRoot kept Store root, or empty for a deleted temporary root
     * @param mdn opt-in external snapshot, or empty when not supplied
     */
    public record Options(Path outputDir, Optional<Path> storeRoot, Optional<MdnOptions> mdn) {

        /**
         * Requires every component and rejects overlapping locations before anything is written: the kept Store
         * roots and the output directory must be pairwise distinct and not nested in each other, in either
         * direction. The supplied snapshot must not be, or resolve to, one of the reserved output files, which a run
         * deletes before it reads any input. Paths are compared in canonical form (see {@link #canonical}), so neither
         * {@code ..} nor a symlinked directory or file can hide an overlap. Other inputs need no such check: kept
         * roots are covered above, the authored corpus is read from the classpath, a snapshot under any other name is
         * never touched, and output cleanup unlinks files instead of truncating them.
         */
        public Options {
            Objects.requireNonNull(outputDir, "outputDir must not be null");
            Objects.requireNonNull(storeRoot, "storeRoot must not be null");
            Objects.requireNonNull(mdn, "mdn must not be null");
            var mdnRoot = mdn.flatMap(MdnOptions::storeRoot);
            if (storeRoot.isPresent() && mdnRoot.isPresent() && overlap(storeRoot.get(), mdnRoot.get())) {
                throw new IllegalArgumentException("--store-root and --mdn-store-root must be separate,"
                        + " non-nested directories");
            }
            for (var root : Stream.concat(storeRoot.stream(), mdnRoot.stream()).toList()) {
                if (overlap(outputDir, root)) {
                    throw new IllegalArgumentException(
                            "--output-dir and a store root must be separate, non-nested directories: " + root);
                }
            }
            if (mdn.isPresent()) {
                var snapshot = canonical(mdn.get().snapshot());
                for (var output : outputFiles(outputDir)) {
                    if (snapshot.equals(canonical(output))) {
                        throw new IllegalArgumentException(
                                "--mdn-snapshot must not be one of the output files of --output-dir: " + output);
                    }
                }
            }
        }

        private static boolean overlap(Path first, Path second) {
            var a = canonical(first);
            var b = canonical(second);
            return a.startsWith(b) || b.startsWith(a);
        }

        /**
         * Returns the absolute path with its longest existing ancestor resolved through {@code toRealPath()}
         * (following symlinks and {@code ..} on disk) and the not-yet-existing remainder appended, normalized.
         */
        static Path canonical(Path path) {
            var absolute = path.toAbsolutePath();
            var existing = absolute;
            while (existing != null && Files.notExists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                return absolute.normalize();
            }
            try {
                return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot resolve " + path + ": " + e.getMessage(), e);
            }
        }

        /** Parses CLI arguments; throws {@link IllegalArgumentException} on invalid usage. */
        public static Options parse(String[] args) {
            var outputDir = Path.of("build/reports/benchmarks");
            Path storeRoot = null;
            Path snapshot = null;
            String revision = null;
            String sha256 = null;
            Path mdnStoreRoot = null;
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--output-dir" -> outputDir = Path.of(value(args, ++i, "--output-dir"));
                    case "--store-root" -> storeRoot = Path.of(value(args, ++i, "--store-root"));
                    case "--mdn-snapshot" -> snapshot = Path.of(value(args, ++i, "--mdn-snapshot"));
                    case "--mdn-revision" -> revision = value(args, ++i, "--mdn-revision");
                    case "--mdn-sha256" -> sha256 = value(args, ++i, "--mdn-sha256");
                    case "--mdn-store-root" -> mdnStoreRoot = Path.of(value(args, ++i, "--mdn-store-root"));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            var mdnGiven = Stream.of(snapshot, revision, sha256, mdnStoreRoot).filter(Objects::nonNull).count();
            if (mdnGiven > 0 && (snapshot == null || revision == null || sha256 == null)) {
                throw new IllegalArgumentException(
                        "--mdn-snapshot, --mdn-revision and --mdn-sha256 must be given together");
            }
            var mdn = snapshot == null
                    ? Optional.<MdnOptions>empty()
                    : Optional.of(new MdnOptions(snapshot, revision, sha256, Optional.ofNullable(mdnStoreRoot)));
            return new Options(outputDir, Optional.ofNullable(storeRoot), mdn);
        }
    }

    /** Opt-in external snapshot options. */
    public record MdnOptions(Path snapshot, String revision, String sha256, Optional<Path> storeRoot) {

        public MdnOptions {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            Objects.requireNonNull(revision, "revision must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
            Objects.requireNonNull(storeRoot, "storeRoot must not be null");
        }
    }

    /** One ingested corpus: its validated labels and its import into one root. */
    public record Ingested(HtmlKnowledgeCorpus corpus, HtmlKnowledgeIngestion.Result result) {
    }

    /**
     * Outcome of a run.
     *
     * @param report rendered verdicts, metadata and environment
     * @param authored the authored corpus import
     * @param external the external snapshot import, when supplied
     * @param outputDir the preflighted output directory that {@link #write(Outcome)} writes to
     */
    public record Outcome(CheckedEvaluationReport report, Ingested authored, Optional<Ingested> external,
            Path outputDir) {
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
        } catch (CorpusValidationException | IngestionInputException e) {
            System.err.println("Ingestion input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.report().toMarkdown());
        try {
            var written = write(outcome);
            System.out.println("Ingestion outputs saved to:");
            written.forEach(path -> System.out.println("  " + path.toAbsolutePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write ingestion outputs", e);
        }
        if (!outcome.report().allPassed()) {
            System.err.println("Semantic ingestion checks failed.");
            System.exit(1);
        }
    }

    /**
     * Runs the experiment. Temporary roots are deleted before returning; a requested {@code --store-root} is kept.
     * Every input, including the snapshot and the freshness of every kept root, is checked before the first import;
     * only a failure during an import (I/O or Store error) can leave a kept root partially written.
     *
     * @throws CorpusValidationException when a corpus or the requested snapshot is rejected
     * @throws IngestionInputException when the output location or a kept root is rejected (for example a duplicate
     *     import)
     * @throws IllegalStateException when the Store maps two chunks to one atom
     */
    public static Outcome run(Options options) {
        Objects.requireNonNull(options, "options must not be null");
        var checks = new ArrayList<HtmlKnowledgeIngestion.Check>();
        var metadata = new TreeMap<String, String>();

        // First of all, so that any later rejection leaves no output of an earlier run behind.
        prepareOutput(options.outputDir());

        var corpus = HtmlKnowledgeCorpus.bundled();
        checks.add(new HtmlKnowledgeIngestion.Check("corpus.valid", true, corpus.version() + ": "
                + corpus.documents().size() + " documents, " + corpus.chunks().size() + " chunks; every label,"
                + " span, digest, bound and the conflict pair validated against canonical chunking"));

        // Validate the external snapshot before writing any store, so a rejected input leaves nothing behind.
        var externalCorpus = options.mdn()
                .map(mdn -> new MdnSnapshotSource().load(mdn.snapshot(), mdn.revision(), mdn.sha256()));

        // Preflight every kept root before importing anything, so a rejected run never leaves a populated Store.
        options.storeRoot().ifPresent(HtmlKnowledgeIngestion::requireFreshRoot);
        options.mdn().flatMap(MdnOptions::storeRoot).ifPresent(HtmlKnowledgeIngestion::requireFreshRoot);

        var authored = new Ingested(corpus, ingest(corpus, options.storeRoot(), "store", checks, metadata));
        describe(corpus, "corpus", metadata);

        Optional<Ingested> external = Optional.empty();
        if (externalCorpus.isPresent()) {
            var mdn = options.mdn().orElseThrow();
            var mdnCorpus = externalCorpus.get();
            var mdnChecks = new ArrayList<HtmlKnowledgeIngestion.Check>();
            mdnChecks.add(new HtmlKnowledgeIngestion.Check("corpus.valid", true, mdnCorpus.version() + ": "
                    + mdnCorpus.chunks().size() + " chunks from the pinned snapshot"));
            external = Optional.of(new Ingested(mdnCorpus,
                    ingest(mdnCorpus, mdn.storeRoot(), "external.mdn.store", mdnChecks, metadata)));
            mdnChecks.forEach(check -> checks.add(new HtmlKnowledgeIngestion.Check(
                    "external.mdn." + check.name(), check.passed(), check.detail())));
            describe(mdnCorpus, "external.mdn.corpus", metadata);
            metadata.put("external.mdn", "supplied: mdn/content@" + mdn.revision() + ", raw sha256 " + mdn.sha256());
            metadata.put("external.mdn.license", MdnSnapshotSource.LICENSE + "; " + MdnSnapshotSource.ATTRIBUTION);
        } else {
            metadata.put("external.mdn", "unavailable (not supplied)");
        }

        metadata.put("protocolVersion", PROTOCOL_VERSION);
        metadata.put("runMode", (options.storeRoot().isPresent() ? "kept Store root" : "default (temporary Store root)")
                + (options.mdn().isPresent() ? " with the external MDN snapshot" : "")
                + "; profile html-knowledge-protocol-v1 corpus ingestion");
        new RunEnvironment().describeHost(metadata);
        metadata.put("corpus.normalization", "strict UTF-8, CRLF/CR to LF, Unicode NFC, trimmed; fenced code kept");
        metadata.put("corpus.chunkingPolicy", CorpusChunker.POLICY);
        metadata.put("store.atomIds", "Store-generated (content-derived); mapped to chunks only through the manifest");
        metadata.put("neuron.version", System.getProperty(NEURON_VERSION_PROPERTY, "unknown"));
        metadata.put("neuron.commit", StoreMetadata.gitCommit(Path.of(".").toAbsolutePath().normalize()));
        var storeDirectory = System.getProperty(STORE_DIRECTORY_PROPERTY);
        metadata.put("store.checkoutCommit",
                StoreMetadata.gitCommit(storeDirectory == null ? null : Path.of(storeDirectory)));

        var evaluation = new EvaluationReport(
                Instant.now(),
                EnvironmentMetadata.current(),
                new RunConfiguration(0L, false, 0, 0, "one import under test per corpus plus "
                        + (LatencyRow.REPETITIONS - 1) + " timed scratch imports; " + LatencyRow.RUN_CONFIGURATION_NOTE),
                List.of(),
                TITLE);
        var report = new CheckedEvaluationReport(TITLE, "Ingestion Metadata", evaluation, checks, metadata);
        return new Outcome(report, authored, external, options.outputDir());
    }

    /**
     * Prepares the output directory before anything else runs: creates it, requires it to be writable and every
     * output file name to be absent or a regular file that is not a symbolic link, then deletes every existing
     * output. A run that starts therefore never leaves an earlier run's report, labels or manifests behind, even
     * when it is rejected later.
     *
     * @throws IngestionInputException when an output cannot be written safely
     */
    static void prepareOutput(Path outputDir) {
        prepareOutput(outputDir, outputFiles(outputDir));
    }

    /** {@link #prepareOutput(Path)} for an explicit set of reserved output {@code files} inside {@code outputDir}. */
    static void prepareOutput(Path outputDir, List<Path> files) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new IngestionInputException("output directory " + outputDir + " cannot be created: " + e, e);
        }
        if (!Files.isDirectory(outputDir) || !Files.isWritable(outputDir)) {
            throw new IngestionInputException("output directory " + outputDir + " is not a writable directory");
        }
        for (var file : files) {
            // Links are never followed: a (possibly dangling) link could point into a Store root that the import
            // is about to create.
            if (Files.isSymbolicLink(file)) {
                throw new IngestionInputException("output file " + file + " is a symbolic link");
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(file))) {
                throw new IngestionInputException("output file " + file + " exists and is not a writable regular file");
            }
        }
        for (var file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new IngestionInputException("cannot remove the earlier output " + file + ": " + e, e);
            }
        }
    }

    private static List<Path> outputFiles(Path outputDir) {
        var files = new ArrayList<Path>(List.of(
                outputDir.resolve("html-knowledge-ingestion.json"), outputDir.resolve("html-knowledge-ingestion.md")));
        for (var version : KNOWN_CORPUS_VERSIONS) {
            files.add(outputDir.resolve(version + "-chunks.tsv"));
            files.add(outputDir.resolve(version + "-manifest.tsv"));
        }
        return files;
    }

    /**
     * Writes the report, label and manifest files into the run's preflighted output directory and returns their
     * paths. The directory was cleared of earlier outputs when the run started.
     */
    public static List<Path> write(Outcome outcome) throws IOException {
        var outputDir = outcome.outputDir();
        var written = new ArrayList<Path>();
        written.add(writeFile(outputDir.resolve("html-knowledge-ingestion.json"), outcome.report().toJson()));
        written.add(writeFile(outputDir.resolve("html-knowledge-ingestion.md"), outcome.report().toMarkdown()));
        for (var ingested : Stream.concat(Stream.of(outcome.authored()), outcome.external().stream()).toList()) {
            var version = ingested.corpus().version();
            written.add(writeFile(outputDir.resolve(version + "-chunks.tsv"), ingested.corpus().labelsTsv()));
            written.add(writeFile(outputDir.resolve(version + "-manifest.tsv"), ingested.result().manifest().toTsv()));
        }
        return written;
    }

    private static HtmlKnowledgeIngestion.Result ingest(HtmlKnowledgeCorpus corpus, Optional<Path> keptRoot,
            String prefix, List<HtmlKnowledgeIngestion.Check> checks, Map<String, String> metadata) {
        var root = keptRoot.orElseGet(HtmlKnowledgeIngestionRunner::temporaryRoot);
        Throwable failure = null;
        try {
            var result = new HtmlKnowledgeIngestion(corpus).ingest(root);
            checks.addAll(result.checks());
            metadata.put(prefix + ".root",
                    keptRoot.isPresent() ? root.toAbsolutePath().toString() : "temporary (deleted)");
            var timings = new HtmlKnowledgeIngestion(corpus).timings(result);
            var scratchKey = "env." + (prefix.equals("store") ? "" : prefix.substring(0, prefix.lastIndexOf('.') + 1))
                    + "timingScratch";
            timings.describe(prefix + ".perf", "readback", scratchKey, metadata);
            new RunEnvironment().describeStoreRoot("env." + prefix + "Root", root, metadata);
            var manifest = root.resolve("manifest.json");
            if (Files.exists(manifest)) {
                StoreMetadata.manifestFields(Files.readString(manifest, StandardCharsets.UTF_8))
                        .forEach((key, value) -> metadata.put(prefix + ".manifest." + key, value));
            }
            return result;
        } catch (IOException e) {
            var unchecked = new UncheckedIOException(e);
            failure = unchecked;
            throw unchecked;
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            if (keptRoot.isEmpty()) {
                try {
                    deleteTree(root);
                } catch (UncheckedIOException cleanup) {
                    // Never let a cleanup error hide why the ingestion failed.
                    if (failure == null) {
                        throw cleanup;
                    }
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }

    private static void describe(HtmlKnowledgeCorpus corpus, String prefix, Map<String, String> metadata) {
        metadata.put(prefix + ".version", corpus.version());
        metadata.put(prefix + ".hash", corpus.corpusHash());
        metadata.put(prefix + ".documents", Integer.toString(corpus.documents().size()));
        metadata.put(prefix + ".chunks", Integer.toString(corpus.chunks().size()));
        metadata.put(prefix + ".bounds", corpus.bounds().describe());
        metadata.put(prefix + ".licenses", corpus.chunks().stream().map(CorpusChunk::license).distinct().sorted()
                .collect(Collectors.joining(", ")));
    }

    /** Writes {@code content}, refusing atomically (O_NOFOLLOW) to follow a symbolic link at {@code path}. */
    static Path writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        return path;
    }

    private static Path temporaryRoot() {
        try {
            // A fresh, empty directory satisfies the duplicate-import guard.
            return Files.createTempDirectory("monada-neuron-html-kc-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void deleteTree(Path root) {
        if (Files.notExists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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
