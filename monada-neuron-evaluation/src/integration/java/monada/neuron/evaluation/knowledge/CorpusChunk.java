package monada.neuron.evaluation.knowledge;

import java.util.Objects;
import java.util.Optional;

/**
 * One labeled, immutable chunk: the unit imported through {@code MonadaMemory.remember}.
 *
 * <p>Labels are canonical and corpus-owned. A Store atom id is never part of a chunk; it is linked only by the
 * {@link IngestionManifest} of one import.
 *
 * @param chunkId {@code chk:<slug>#<nn>}, two-digit ordinal in document order starting at {@code 00}
 * @param documentId owning {@code doc:<slug>}
 * @param topic authored topic label
 * @param section governing heading text
 * @param source provenance copied from the document
 * @param license license copied from the document
 * @param attribution attribution copied from the document
 * @param start first code point of the span in the normalized document (inclusive)
 * @param end last code point of the span (exclusive)
 * @param sha256 SHA-256 of the UTF-8 bytes of {@code text}
 * @param documentSha256 SHA-256 of the normalized owning document
 * @param conflict conflicting-source pair membership, when any
 * @param text normalized chunk text
 */
public record CorpusChunk(
        String chunkId,
        String documentId,
        String topic,
        String section,
        String source,
        String license,
        String attribution,
        int start,
        int end,
        String sha256,
        String documentSha256,
        Optional<Conflict> conflict,
        String text) {

    /** Membership in the conflicting-source pair {@code pairId}. */
    public record Conflict(String pairId, ConflictRole role) {

        public Conflict {
            Objects.requireNonNull(pairId, "pairId must not be null");
            Objects.requireNonNull(role, "role must not be null");
        }
    }

    public CorpusChunk {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(section, "section must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(license, "license must not be null");
        Objects.requireNonNull(attribution, "attribution must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        Objects.requireNonNull(documentSha256, "documentSha256 must not be null");
        Objects.requireNonNull(conflict, "conflict must not be null");
        Objects.requireNonNull(text, "text must not be null");
    }
}
