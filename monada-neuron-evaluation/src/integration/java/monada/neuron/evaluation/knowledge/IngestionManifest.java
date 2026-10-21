package monada.neuron.evaluation.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-import link {@code chunkId → atomId → opaque reference} of one Store root.
 *
 * <p>It is built only from what {@code remember} returned during that import, never from gold labels and never by
 * predicting ids, and it is the only way a retrieved atom or Neuron reference maps back to a chunk. It holds no
 * content hashes (content checks compare against the corpus {@code sha256}) and never enters a dataset hash or a
 * cross-run comparison.
 */
public final class IngestionManifest {

    /** Header row of the TSV rendering. */
    public static final String HEADER = "chunkId\tatomId\topaqueReference";

    /**
     * One imported chunk.
     *
     * @param chunkId canonical corpus label
     * @param atomId id of the {@code KnowledgeAtom} the Store returned for the chunk
     * @param opaqueReference the adapter's {@code OpaqueReference.of(atomId)}
     */
    public record Entry(String chunkId, String atomId, String opaqueReference) {

        public Entry {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(atomId, "atomId must not be null");
            Objects.requireNonNull(opaqueReference, "opaqueReference must not be null");
        }
    }

    private final String corpusVersion;
    private final String corpusHash;
    private final List<Entry> entries;
    private final Map<String, String> chunkIdByAtomId;
    private final Map<String, String> chunkIdByReference;
    private final Map<String, Entry> entryByChunkId;

    /** Creates a manifest in import order; atom ids and references must be unique. */
    public IngestionManifest(String corpusVersion, String corpusHash, List<Entry> entries) {
        this.corpusVersion = Objects.requireNonNull(corpusVersion, "corpusVersion must not be null");
        this.corpusHash = Objects.requireNonNull(corpusHash, "corpusHash must not be null");
        this.entries = List.copyOf(entries);
        var byAtom = new HashMap<String, String>();
        var byReference = new HashMap<String, String>();
        var byChunk = new HashMap<String, Entry>();
        for (var entry : this.entries) {
            byChunk.putIfAbsent(entry.chunkId(), entry);
            if (byAtom.put(entry.atomId(), entry.chunkId()) != null
                    || byReference.put(entry.opaqueReference(), entry.chunkId()) != null) {
                throw new IllegalArgumentException("atom " + entry.atomId() + " is mapped to more than one chunk");
            }
        }
        this.chunkIdByAtomId = Map.copyOf(byAtom);
        this.chunkIdByReference = Map.copyOf(byReference);
        this.entryByChunkId = Map.copyOf(byChunk);
    }

    /** Returns the corpus version that was imported. */
    public String corpusVersion() {
        return corpusVersion;
    }

    /** Returns the {@code corpusHash} that was imported. */
    public String corpusHash() {
        return corpusHash;
    }

    /** Returns the entries in import order. */
    public List<Entry> entries() {
        return entries;
    }

    /** Maps a Store atom id of this root back to its chunk id. */
    public Optional<String> chunkIdForAtom(String atomId) {
        return Optional.ofNullable(chunkIdByAtomId.get(atomId));
    }

    /** Returns the entry of {@code chunkId}, the only way an experiment turns a chunk label into this root's atom. */
    public Optional<Entry> entryForChunk(String chunkId) {
        return Optional.ofNullable(entryByChunkId.get(chunkId));
    }

    /** Maps an adapter opaque reference of this root back to its chunk id. */
    public Optional<String> chunkIdForReference(String opaqueReference) {
        return Optional.ofNullable(chunkIdByReference.get(opaqueReference));
    }

    /** Renders the manifest as TSV with a commented provenance header. */
    public String toTsv() {
        var sb = new StringBuilder();
        sb.append("# corpusVersion: ").append(corpusVersion).append('\n');
        sb.append("# corpusHash: ").append(corpusHash).append('\n');
        sb.append("# Atom ids are Store-generated for this root; map them to chunks only through this file.\n");
        sb.append(HEADER).append('\n');
        for (var entry : entries) {
            sb.append(entry.chunkId()).append('\t').append(entry.atomId()).append('\t')
                    .append(entry.opaqueReference()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Parses a {@link #toTsv()} rendering, for example in a later process that reopens the same root. Parsing is
     * strict: the provenance header, the column header and three non-empty columns per row are required, and the
     * corpus version and hash must equal the expected ones.
     *
     * @throws IllegalArgumentException when the text is not a manifest of {@code expectedVersion} and
     *     {@code expectedHash}, or maps an atom or reference to more than one chunk
     */
    /**
     * Requires one entry per chunk of {@code corpus}, in the corpus's {@code chunkId} order, so a manifest file cut at a
     * row boundary is rejected instead of silently mapping fewer chunks.
     *
     * @throws IngestionInputException when an entry is missing, extra or out of order
     */
    public IngestionManifest requireComplete(HtmlKnowledgeCorpus corpus) {
        var expected = corpus.chunks().stream().map(CorpusChunk::chunkId).toList();
        var actual = entries.stream().map(Entry::chunkId).toList();
        if (!actual.equals(expected)) {
            throw new IngestionInputException("manifest maps " + actual.size() + " chunks, expected all "
                    + expected.size() + " chunks of " + corpus.version() + " in chunkId order");
        }
        return this;
    }

    public static IngestionManifest parseTsv(String tsv, String expectedVersion, String expectedHash) {
        Objects.requireNonNull(tsv, "tsv must not be null");
        var lines = tsv.lines().toList();
        String version = null;
        String hash = null;
        var index = 0;
        for (; index < lines.size() && lines.get(index).startsWith("#"); index++) {
            var line = lines.get(index);
            if (line.startsWith("# corpusVersion: ")) {
                version = line.substring("# corpusVersion: ".length());
            } else if (line.startsWith("# corpusHash: ")) {
                hash = line.substring("# corpusHash: ".length());
            }
        }
        if (!Objects.equals(version, expectedVersion) || !Objects.equals(hash, expectedHash)) {
            throw new IllegalArgumentException("manifest is for corpus " + version + " (" + hash + "), expected "
                    + expectedVersion + " (" + expectedHash + ")");
        }
        if (index >= lines.size() || !lines.get(index).equals(HEADER)) {
            throw new IllegalArgumentException("manifest column header missing, expected: " + HEADER);
        }
        var entries = new ArrayList<Entry>(lines.size() - index - 1);
        for (index++; index < lines.size(); index++) {
            var columns = lines.get(index).split("\t", -1);
            if (columns.length != 3 || Arrays.stream(columns).anyMatch(String::isEmpty)) {
                throw new IllegalArgumentException("malformed manifest row " + (index + 1) + ": " + lines.get(index));
            }
            entries.add(new Entry(columns[0], columns[1], columns[2]));
        }
        return new IngestionManifest(version, hash, entries);
    }
}
