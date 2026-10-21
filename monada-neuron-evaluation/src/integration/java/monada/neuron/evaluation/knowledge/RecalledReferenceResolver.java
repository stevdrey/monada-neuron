package monada.neuron.evaluation.knowledge;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The evaluation-only host component that joins the opaque references a Neuron cycle carries to verifiably persisted
 * source chunks (Issue #108). It is built from one root's import manifest and that root's readback in the current
 * process: a reference resolves only when its chunk's atom was read back from the reopened Store with content bytes
 * that hash to the corpus {@code sha256}.
 *
 * <p>It never reads gold labels. Resolution is one lookup in a fixed map of at most the corpus size, so resolving the
 * {@code K} references of one cycle is {@code O(K)}. A reference that is unknown, or whose chunk was not verified, fails
 * closed: {@link #resolve} returns no chunk and the caller must not report support from it.
 */
final class RecalledReferenceResolver {

    /** The outcome of resolving one reference. */
    enum Status {
        /** The reference maps to a chunk whose persisted bytes were verified in this process. */
        RESOLVED,
        /** The reference is not in the manifest. */
        UNKNOWN,
        /** The reference is in the manifest, but its chunk was not read back with matching bytes. */
        UNVERIFIED
    }

    /**
     * One resolution.
     *
     * @param status the outcome
     * @param chunkId the mapped chunk, present unless {@link Status#UNKNOWN}
     * @param sha256 the verified digest of the persisted bytes, present only when {@link Status#RESOLVED}
     */
    record Resolution(Status status, Optional<String> chunkId, Optional<String> sha256) {

        Resolution {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(sha256, "sha256 must not be null");
            if (status == Status.RESOLVED != sha256.isPresent() || status == Status.UNKNOWN == chunkId.isPresent()) {
                throw new IllegalArgumentException("inconsistent resolution " + status + " " + chunkId + " " + sha256);
            }
        }

        boolean resolved() {
            return status == Status.RESOLVED;
        }
    }

    private record Target(String chunkId, String sha256, boolean verified) {
    }

    private final Map<String, Target> byReference;

    /**
     * Builds the resolver of one root.
     *
     * @param corpus the corpus the root was imported from
     * @param manifest that root's import manifest, complete for {@code corpus}; {@link IngestionManifest} already
     *     rejects two entries sharing an atom or a reference, so an ambiguous pointer cannot reach the resolver
     * @param unverifiedChunkIds the chunks the readback in this process did not read back with matching bytes
     * @throws IllegalArgumentException when a manifest chunk is not in the corpus
     */
    RecalledReferenceResolver(HtmlKnowledgeCorpus corpus, IngestionManifest manifest, Set<String> unverifiedChunkIds) {
        Objects.requireNonNull(corpus, "corpus must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        var unverified = Set.copyOf(unverifiedChunkIds);
        var targets = new HashMap<String, Target>(manifest.entries().size() * 2);
        for (var entry : manifest.entries()) {
            var chunk = corpus.chunk(entry.chunkId()).orElseThrow(() -> new IllegalArgumentException(
                    "manifest chunk " + entry.chunkId() + " is not in corpus " + corpus.version()));
            targets.put(entry.opaqueReference(),
                    new Target(chunk.chunkId(), chunk.sha256(), !unverified.contains(chunk.chunkId())));
        }
        this.byReference = Map.copyOf(targets);
    }

    /** Resolves one reference; never throws for an unknown one. */
    Resolution resolve(String reference) {
        var target = byReference.get(Objects.requireNonNull(reference, "reference must not be null"));
        if (target == null) {
            return new Resolution(Status.UNKNOWN, Optional.empty(), Optional.empty());
        }
        return target.verified()
                ? new Resolution(Status.RESOLVED, Optional.of(target.chunkId()), Optional.of(target.sha256()))
                : new Resolution(Status.UNVERIFIED, Optional.of(target.chunkId()), Optional.empty());
    }
}
