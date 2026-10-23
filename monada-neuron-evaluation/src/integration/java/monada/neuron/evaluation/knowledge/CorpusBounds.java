package monada.neuron.evaluation.knowledge;

/**
 * Load-time bounds of a corpus. A violation fails the load before hashing or import.
 *
 * @param minChunks minimum number of chunks
 * @param maxChunks maximum number of chunks
 * @param maxChunkBytes maximum UTF-8 size of one normalized chunk
 * @param maxChunksPerDocument maximum chunks of one document
 * @param maxDocuments maximum number of documents
 * @param maxDocumentBytes maximum raw size of one document file
 * @param requireConflictPair whether exactly one conflicting-source pair is required
 */
public record CorpusBounds(
        int minChunks,
        int maxChunks,
        int maxChunkBytes,
        int maxChunksPerDocument,
        int maxDocuments,
        int maxDocumentBytes,
        boolean requireConflictPair) {

    /** Bounds of the authored {@code html-kc-v1} corpus, as fixed by the protocol. */
    public static final CorpusBounds AUTHORED = new CorpusBounds(20, 40, 1024, 8, 16, 32 * 1024, true);

    /** Bounds of one user-supplied external tutorial snapshot (a single, longer document). */
    public static final CorpusBounds EXTERNAL = new CorpusBounds(1, 100, 1024, 100, 1, 256 * 1024, false);

    /**
     * Bounds of a scaling-family point {@code html-kc-v1-scale-<N>} (Issue #111): 25 to 500 chunks with the authored
     * per-chunk and per-document limits; every generated distractor is its own single-chunk document.
     */
    public static final CorpusBounds SCALING = new CorpusBounds(25, 500, 1024, 8, 512, 32 * 1024, true);

    /**
     * Bounds of the authored decision supplement {@code html-kd-v1} (Issue #112): one document of at most 8 chunks,
     * with the authored per-chunk limit and no conflict pair of its own.
     */
    public static final CorpusBounds DECISION_SUPPLEMENT = new CorpusBounds(1, 8, 1024, 8, 1, 32 * 1024, false);

    /** Chunk ids carry a two-digit ordinal ({@code 00} to {@code 99}), so a document holds at most 100 chunks. */
    public static final int MAX_ORDINALS = 100;

    public CorpusBounds {
        if (minChunks < 1 || maxChunks < minChunks || maxChunkBytes < 1 || maxChunksPerDocument < 1
                || maxDocuments < 1 || maxDocumentBytes < 1) {
            throw new IllegalArgumentException("corpus bounds must be positive and ordered");
        }
        if (maxChunksPerDocument > MAX_ORDINALS) {
            throw new IllegalArgumentException("maxChunksPerDocument must not exceed " + MAX_ORDINALS
                    + ", the number of two-digit chunk ordinals");
        }
    }

    /** Renders the bounds for reports. */
    public String describe() {
        return "chunks " + minChunks + ".." + maxChunks + ", <= " + maxChunkBytes + " B/chunk, <= "
                + maxChunksPerDocument + " chunks/document, <= " + maxDocuments + " documents, <= "
                + maxDocumentBytes + " B/document, conflictPair " + (requireConflictPair ? "required" : "not required");
    }
}
