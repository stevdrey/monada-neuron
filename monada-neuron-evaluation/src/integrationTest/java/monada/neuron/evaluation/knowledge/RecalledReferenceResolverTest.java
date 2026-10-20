package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.RecalledReferenceResolver.Status;
import monada.neuron.resonance.adapter.OpaqueReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The host-side join of opaque references to verified persisted chunks fails closed. */
class RecalledReferenceResolverTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundled();
    private static final OpaqueReference REFERENCE = new OpaqueReference();

    @Test
    void aVerifiedReferenceResolvesToItsChunkAndDigest() {
        var manifest = manifest(entries());
        var chunk = CORPUS.chunks().get(3);
        var resolution = new RecalledReferenceResolver(CORPUS, manifest, Set.of())
                .resolve(manifest.entries().get(3).opaqueReference());
        assertEquals(Status.RESOLVED, resolution.status());
        assertEquals(Optional.of(chunk.chunkId()), resolution.chunkId());
        assertEquals(Optional.of(chunk.sha256()), resolution.sha256());
    }

    @Test
    void unknownAndUnverifiedReferencesFailClosed() {
        var manifest = manifest(entries());
        var unverified = CORPUS.chunks().get(0).chunkId();
        var resolver = new RecalledReferenceResolver(CORPUS, manifest, Set.of(unverified));
        assertEquals(Status.UNKNOWN, resolver.resolve(REFERENCE.of("not-an-imported-atom")).status());
        var resolution = resolver.resolve(manifest.entries().get(0).opaqueReference());
        assertEquals(Status.UNVERIFIED, resolution.status());
        assertEquals(Optional.empty(), resolution.sha256(), "an unverified chunk has no trusted digest");
    }

    @Test
    void anAmbiguousOrForeignManifestIsRejected() {
        var entries = entries();
        var first = entries.get(0);
        var second = entries.get(1);
        entries.set(1, new IngestionManifest.Entry(second.chunkId(), second.atomId(), first.opaqueReference()));
        // One reference for two chunks is an ambiguous pointer: no manifest, and so no resolver, can hold it.
        assertThrows(IllegalArgumentException.class, () -> manifest(entries));
        var removed = HtmlKnowledgeEvidenceTracePhase.withoutDocument(CORPUS,
                HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT);
        assertThrows(IllegalArgumentException.class, () -> new RecalledReferenceResolver(removed,
                manifest(entries()), Set.of()), "a chunk of the removed document is not in the removed corpus");
    }

    private static List<IngestionManifest.Entry> entries() {
        var entries = new ArrayList<IngestionManifest.Entry>();
        for (var index = 0; index < CORPUS.chunks().size(); index++) {
            var atom = "atom-" + index;
            entries.add(new IngestionManifest.Entry(CORPUS.chunks().get(index).chunkId(), atom, REFERENCE.of(atom)));
        }
        return entries;
    }

    private static IngestionManifest manifest(List<IngestionManifest.Entry> entries) {
        return new IngestionManifest(CORPUS.version(), CORPUS.corpusHash(), entries);
    }
}
