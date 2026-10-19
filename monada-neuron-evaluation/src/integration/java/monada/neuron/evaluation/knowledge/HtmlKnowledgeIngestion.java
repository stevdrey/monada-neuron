package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.core.KnowledgeAtom;
import monada.neuron.evaluation.metrics.EvaluationCheck;
import monada.neuron.resonance.adapter.OpaqueReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Imports a validated corpus into one Store root through the published {@code MonadaMemory.remember} API and links
 * every chunk to the atom the Store returned.
 *
 * <p>The Store derives atom ids from content and {@code remember} is idempotent, so the Store itself cannot report
 * a second import of the same corpus. The harness therefore refuses any target root that already holds files
 * (duplicate-import guard), and corpus validation already rejects identical chunk texts that would collapse into
 * one atom. Ids are never predicted: the {@link IngestionManifest} records what {@code remember} returned.
 *
 * <p>After the import, every chunk is read back through the read-only {@code resonate} with its own normalized text
 * and {@code topK} equal to the corpus size: the result must contain the mapped atom and that atom's content must
 * hash to the corpus {@code sha256}. The public Store API has no export or read by id, so this detects a chunk that
 * was lost or corrupted even if no query would ever recall it.
 */
public final class HtmlKnowledgeIngestion {

    /** Readback threshold: zero, so the readback depends on presence, not on a score cutoff. */
    public static final double READBACK_THRESHOLD = 0.0;

    private final HtmlKnowledgeCorpus corpus;
    private final OpaqueReference opaqueReference = new OpaqueReference();

    /** Creates an ingestion of {@code corpus}. */
    public HtmlKnowledgeIngestion(HtmlKnowledgeCorpus corpus) {
        this.corpus = Objects.requireNonNull(corpus, "corpus must not be null");
    }

    /** A named semantic verdict of the ingestion. */
    public record Check(String name, boolean passed, String detail) implements EvaluationCheck {

        public Check {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    /**
     * Outcome of one import.
     *
     * @param root Store root that was written
     * @param manifest chunk-to-atom links of this root
     * @param checks semantic verdicts, in a fixed order
     * @param ingestNanos wall time of opening the Store and remembering every chunk (exploratory)
     * @param readbackNanos wall time of the readback (exploratory)
     */
    public record Result(Path root, IngestionManifest manifest, List<Check> checks, long ingestNanos,
            long readbackNanos) {

        public Result {
            Objects.requireNonNull(root, "root must not be null");
            Objects.requireNonNull(manifest, "manifest must not be null");
            checks = List.copyOf(checks);
        }

        /** Returns whether every check passed. */
        public boolean allPassed() {
            return checks.stream().allMatch(Check::passed);
        }
    }

    /**
     * Imports the corpus into {@code root}, which must not exist yet or be an empty directory.
     *
     * @throws IllegalStateException when the root already holds files (duplicate import) or the Store returned
     *     the same atom for two chunks
     */
    public Result ingest(Path root) {
        Objects.requireNonNull(root, "root must not be null");
        requireFreshRoot(root);

        var start = System.nanoTime();
        var memory = MonadaMemory.open(root);
        var atoms = new ArrayList<KnowledgeAtom>(corpus.chunks().size());
        for (var chunk : corpus.chunks()) {
            atoms.add(memory.remember(chunk.text()));
        }
        var ingestNanos = System.nanoTime() - start;

        var entries = new ArrayList<IngestionManifest.Entry>(atoms.size());
        var chunkByAtom = new HashMap<String, String>();
        var mismatched = new ArrayList<String>();
        for (var i = 0; i < atoms.size(); i++) {
            var chunk = corpus.chunks().get(i);
            var atom = atoms.get(i);
            var previous = chunkByAtom.putIfAbsent(atom.id(), chunk.chunkId());
            if (previous != null) {
                throw new IllegalStateException("the Store returned one atom for " + previous + " and "
                        + chunk.chunkId() + "; each chunk must map to its own atom");
            }
            if (!CorpusDigests.sha256(atom.content()).equals(chunk.sha256())) {
                mismatched.add(chunk.chunkId());
            }
            entries.add(new IngestionManifest.Entry(chunk.chunkId(), atom.id(), opaqueReference.of(atom.id())));
        }
        var manifest = new IngestionManifest(corpus.version(), corpus.corpusHash(), entries);

        var readbackStart = System.nanoTime();
        var unread = readback(memory, manifest);
        var readbackNanos = System.nanoTime() - readbackStart;

        var count = corpus.chunks().size();
        var checks = List.of(
                new Check("ingestion.manifestComplete", entries.size() == count,
                        entries.size() + " of " + count + " chunks mapped to distinct atoms, in chunkId order"),
                new Check("ingestion.contentFidelity", mismatched.isEmpty(), mismatched.isEmpty()
                        ? "every remembered atom's content hashes to its chunk sha256"
                        : "content sha256 differs for " + mismatched),
                new Check("ingestion.readback", unread.isEmpty(), unread.isEmpty()
                        ? "every chunk recalled its mapped atom with matching sha256 via resonate(text).topK("
                                + count + ").threshold(" + READBACK_THRESHOLD + ")"
                        : "readback failed for " + unread));
        return new Result(root, manifest, checks, ingestNanos, readbackNanos);
    }

    /**
     * Reads every manifest entry back through {@code resonate(chunk text).topK(corpus size)} on {@code memory} and
     * returns the chunk ids whose mapped atom was not recalled with content hashing to the chunk {@code sha256}, in
     * manifest order; empty when every chunk was read back.
     */
    List<String> readback(MonadaMemory memory, IngestionManifest manifest) {
        return readbackSweep(memory, manifest).unread();
    }

    /**
     * Result of one readback pass.
     *
     * @param unread chunk ids that were not read back, in manifest order
     * @param recalledAtomIds every atom id any readback query returned
     */
    record Readback(List<String> unread, Set<String> recalledAtomIds) {

        Readback {
            unread = List.copyOf(unread);
            recalledAtomIds = Set.copyOf(recalledAtomIds);
        }
    }

    /** {@link #readback} that also collects every recalled atom id, from the same queries. */
    Readback readbackSweep(MonadaMemory memory, IngestionManifest manifest) {
        var failures = new ArrayList<String>();
        var recalled = new HashSet<String>();
        var count = corpus.chunks().size();
        for (var entry : manifest.entries()) {
            var chunk = corpus.chunk(entry.chunkId()).orElseThrow();
            var found = false;
            for (var result : memory.resonate(chunk.text()).topK(count).threshold(READBACK_THRESHOLD).execute()
                    .results()) {
                recalled.add(result.atom().id());
                found |= result.atom().id().equals(entry.atomId())
                        && CorpusDigests.sha256(result.atom().content()).equals(chunk.sha256());
            }
            if (!found) {
                failures.add(entry.chunkId());
            }
        }
        return new Readback(failures, recalled);
    }

    /**
     * Latency rows of an import: {@code ingest} (Store open plus one {@code remember} per chunk) and {@code readback}
     * (one {@code resonate} per chunk, hashing in the window), each with up to {@link LatencyRow#REPETITIONS}
     * samples, and {@code ingestion.timingImportsCompleted}, which fails when a scratch import failed.
     */
    record ImportTimings(LatencyRow ingest, LatencyRow readback, Check check) {

        /** Records both rows as {@code <prefix>.ingest} and {@code <prefix>.<readbackRow>}. */
        void describe(String prefix, String readbackRow, Map<String, String> metadata) {
            ingest.describe(prefix + ".ingest", metadata);
            readback.describe(prefix + "." + readbackRow, metadata);
        }
    }

    /**
     * Times {@code first}, an import already made, together with {@link LatencyRow#REPETITIONS} − 1 further imports
     * of the same corpus into fresh roots under {@code scratch}. Each extra root is deleted right after its import. A
     * sample counts only when its import passed every check; a failed or throwing scratch import is excluded from both
     * rows and reported by the returned check, so a broken import is never published as a latency sample.
     */
    ImportTimings timings(Result first, Path scratch) {
        var ingest = new ArrayList<Long>();
        var readback = new ArrayList<Long>();
        var failures = new ArrayList<String>();
        if (first.allPassed()) {
            ingest.add(first.ingestNanos());
            readback.add(first.readbackNanos());
        } else {
            failures.add("the import under test failed a check");
        }
        for (var repetition = 1; repetition < LatencyRow.REPETITIONS; repetition++) {
            var root = scratch.resolve("timing-import-" + repetition);
            try {
                var extra = ingest(root);
                if (extra.allPassed()) {
                    ingest.add(extra.ingestNanos());
                    readback.add(extra.readbackNanos());
                } else {
                    failures.add("scratch import " + repetition + " failed " + extra.checks().stream()
                            .filter(check -> !check.passed()).map(Check::name).toList());
                }
            } catch (RuntimeException e) {
                failures.add("scratch import " + repetition + " threw " + e);
            } finally {
                HtmlKnowledgeIngestionRunner.deleteTree(root);
            }
        }
        var check = new Check("ingestion.timingImportsCompleted", failures.isEmpty(), failures.isEmpty()
                ? LatencyRow.REPETITIONS + " timed imports (the import under test and " + (LatencyRow.REPETITIONS - 1)
                        + " scratch imports) passed every check"
                : "excluded from the latency rows: " + String.join("; ", failures));
        var chunks = corpus.chunks().size();
        return new ImportTimings(
                LatencyRow.wallClock(ingest.stream().mapToLong(Long::longValue).toArray(), chunks, "one Store open"
                        + " plus one remember per chunk into a fresh root; sample 1 is the import under test, the others"
                        + " go to deleted scratch roots; failed imports are excluded", false),
                LatencyRow.wallClock(readback.stream().mapToLong(Long::longValue).toArray(), chunks, "one resonate"
                        + " per chunk on the just-imported root, with the content hash check in the window", true),
                check);
    }

    /**
     * Requires {@code root} to be absent or an empty directory, the duplicate-import guard.
     *
     * @throws IngestionInputException when the root is not a directory or already holds files
     */
    static void requireFreshRoot(Path root) {
        if (Files.notExists(root)) {
            return;
        }
        if (!Files.isDirectory(root)) {
            throw new IngestionInputException("target root " + root + " is not a directory");
        }
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                throw new IngestionInputException("target root " + root
                        + " already holds files (an existing store); duplicate import refused");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot inspect target root " + root, e);
        }
    }
}
