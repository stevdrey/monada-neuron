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
import java.nio.file.Path;
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
         * direction. Paths are compared after {@code toAbsolutePath().normalize()}.
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
        }

        private static boolean overlap(Path first, Path second) {
            var a = absolute(first);
            var b = absolute(second);
            return a.startsWith(b) || b.startsWith(a);
        }

        private static Path absolute(Path path) {
            return path.toAbsolutePath().normalize();
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
     */
    public record Outcome(CheckedEvaluationReport report, Ingested authored, Optional<Ingested> external) {
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
        } catch (CorpusValidationException | IllegalStateException e) {
            System.err.println("Ingestion input rejected: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(outcome.report().toMarkdown());
        try {
            var written = write(outcome, options.outputDir());
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
     * @throws IllegalStateException when a target root already holds a store (duplicate import)
     */
    public static Outcome run(Options options) {
        Objects.requireNonNull(options, "options must not be null");
        var checks = new ArrayList<HtmlKnowledgeIngestion.Check>();
        var metadata = new TreeMap<String, String>();

        var corpus = HtmlKnowledgeCorpus.bundled();
        var reloaded = HtmlKnowledgeCorpus.bundled();
        checks.add(new HtmlKnowledgeIngestion.Check("corpus.valid", true, corpus.version() + ": "
                + corpus.documents().size() + " documents, " + corpus.chunks().size() + " chunks; every label,"
                + " span, digest, bound and the conflict pair validated against canonical chunking"));
        var deterministic = corpus.chunks().equals(reloaded.chunks())
                && corpus.corpusHash().equals(reloaded.corpusHash());
        checks.add(new HtmlKnowledgeIngestion.Check("corpus.deterministicReload", deterministic, deterministic
                ? "a second load yields identical chunk labels, order, spans, fingerprints and corpusHash"
                : "a second load differs"));

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
                new RunConfiguration(0L, false, 0, 0,
                        "single import per corpus; timings in metadata are one exploratory wall-clock sample"),
                List.of(),
                TITLE);
        var report = new CheckedEvaluationReport(TITLE, "Ingestion Metadata", evaluation, checks, metadata);
        return new Outcome(report, authored, external);
    }

    /**
     * Writes the report, label and manifest files and returns their paths. Corpus files of a known corpus version
     * that this run did not produce (for example an external snapshot of an earlier run) are deleted, so the
     * directory never shows labels or a manifest that the current report does not describe.
     */
    public static List<Path> write(Outcome outcome, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        var written = new ArrayList<Path>();
        written.add(writeFile(outputDir.resolve("html-knowledge-ingestion.json"), outcome.report().toJson()));
        written.add(writeFile(outputDir.resolve("html-knowledge-ingestion.md"), outcome.report().toMarkdown()));
        var produced = new ArrayList<String>();
        for (var ingested : Stream.concat(Stream.of(outcome.authored()), outcome.external().stream()).toList()) {
            var version = ingested.corpus().version();
            produced.add(version);
            written.add(writeFile(outputDir.resolve(version + "-chunks.tsv"), ingested.corpus().labelsTsv()));
            written.add(writeFile(outputDir.resolve(version + "-manifest.tsv"), ingested.result().manifest().toTsv()));
        }
        for (var version : KNOWN_CORPUS_VERSIONS) {
            if (!produced.contains(version)) {
                Files.deleteIfExists(outputDir.resolve(version + "-chunks.tsv"));
                Files.deleteIfExists(outputDir.resolve(version + "-manifest.tsv"));
            }
        }
        return written;
    }

    private static HtmlKnowledgeIngestion.Result ingest(HtmlKnowledgeCorpus corpus, Optional<Path> keptRoot,
            String prefix, List<HtmlKnowledgeIngestion.Check> checks, Map<String, String> metadata) {
        var root = keptRoot.orElseGet(HtmlKnowledgeIngestionRunner::temporaryRoot);
        try {
            var result = new HtmlKnowledgeIngestion(corpus).ingest(root);
            checks.addAll(result.checks());
            metadata.put(prefix + ".root",
                    keptRoot.isPresent() ? root.toAbsolutePath().toString() : "temporary (deleted)");
            metadata.put(prefix + ".perf.ingestNanos", Long.toString(result.ingestNanos()));
            metadata.put(prefix + ".perf.readbackNanos", Long.toString(result.readbackNanos()));
            var manifest = root.resolve("manifest.json");
            if (Files.exists(manifest)) {
                StoreMetadata.manifestFields(Files.readString(manifest, StandardCharsets.UTF_8))
                        .forEach((key, value) -> metadata.put(prefix + ".manifest." + key, value));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (keptRoot.isEmpty()) {
                deleteTree(root);
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

    private static Path writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
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

    private static void deleteTree(Path root) {
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
