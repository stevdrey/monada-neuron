package monada.neuron.evaluation.knowledge;

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

    /** Creates a manifest in import order; atom ids and references must be unique. */
    public IngestionManifest(String corpusVersion, String corpusHash, List<Entry> entries) {
        this.corpusVersion = Objects.requireNonNull(corpusVersion, "corpusVersion must not be null");
        this.corpusHash = Objects.requireNonNull(corpusHash, "corpusHash must not be null");
        this.entries = List.copyOf(entries);
        var byAtom = new HashMap<String, String>();
        var byReference = new HashMap<String, String>();
        for (var entry : this.entries) {
            if (byAtom.put(entry.atomId(), entry.chunkId()) != null
                    || byReference.put(entry.opaqueReference(), entry.chunkId()) != null) {
                throw new IllegalArgumentException("atom " + entry.atomId() + " is mapped to more than one chunk");
            }
        }
        this.chunkIdByAtomId = Map.copyOf(byAtom);
        this.chunkIdByReference = Map.copyOf(byReference);
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
}
