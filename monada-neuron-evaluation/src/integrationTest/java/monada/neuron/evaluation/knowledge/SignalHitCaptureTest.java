package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.resonance.adapter.ResonanceStoreRecall;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Captures over one real import of the bundled base corpus. */
class SignalHitCaptureTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundled();
    private static final QueryText QUERY = new QueryText("q:test-01", "Which element marks up a hyperlink?");

    @TempDir
    static Path directory;

    private static IngestionManifest manifest;
    private static ResonanceStoreRecall store;

    @BeforeAll
    static void importOnce() {
        var root = directory.resolve("root");
        manifest = new HtmlKnowledgeIngestion(CORPUS).ingest(root).manifest();
        var memory = MonadaMemory.open(root);
        store = (query, limit, threshold) -> memory.resonate(query).topK(limit).threshold(threshold).execute()
                .results();
    }

    @Test
    void everyCodecSignalIsCapturedAtEveryPoolWithinItsBound() {
        var capture = new SignalHitCapture(CORPUS, manifest, store).capture(QUERY);

        assertTrue(capture.completed(), capture.failure().orElse(""));
        assertEquals(List.copyOf(HtmlTokens.distinct(QUERY.text())), capture.tokens(),
                "one single-token Store query per distinct token, in first-occurrence order");
        assertEquals(capture.tokens().size(), capture.storeCalls());
        for (var pool : SignalHitCapture.POOLS) {
            var lists = capture.at(pool);
            assertEquals(capture.tokens().size(), lists.size());
            for (var signal = 0; signal < lists.size(); signal++) {
                var hits = lists.get(signal);
                assertTrue(hits.size() <= pool, "pool " + pool);
                for (var rank = 0; rank < hits.size(); rank++) {
                    assertEquals(signal, hits.get(rank).signalIndex());
                    assertEquals(rank, hits.get(rank).rank());
                    assertTrue(CORPUS.chunk(hits.get(rank).chunkId()).isPresent());
                    assertEquals(manifest.chunkIdForReference(hits.get(rank).reference()),
                            Optional.of(hits.get(rank).chunkId()), "the reference is opaque but maps");
                }
            }
        }
    }

    @Test
    void aRecaptureIsBitIdentical() {
        var capture = new SignalHitCapture(CORPUS, manifest, store);

        assertEquals(capture.capture(QUERY), capture.capture(QUERY));
    }

    @Test
    void aBlankQueryTravelsAsTheEmptyStoreQuery() {
        var capture = new SignalHitCapture(CORPUS, manifest, store).capture(new QueryText("q:blank-01", "  "));

        assertTrue(capture.completed());
        assertEquals(List.of(""), capture.tokens());
        assertTrue(capture.at(5).getFirst().isEmpty(), "the Store returns nothing for the empty query");
    }

    @Test
    void aThrowingStoreFailsTheWholeQuery() {
        ResonanceStoreRecall throwing = (query, limit, threshold) -> {
            throw new IllegalStateException("boom");
        };
        var capture = new SignalHitCapture(CORPUS, manifest, throwing).capture(QUERY);

        assertFalse(capture.completed());
        assertTrue(capture.failure().orElseThrow().contains("boom"));
        assertTrue(capture.lists().isEmpty(), "a failed query has no lists to score");
    }

    @Test
    void aNonFiniteScoreAnUnmappedAtomOrChangedContentFailTheQuery() {
        var mapped = manifest.entries().getFirst();
        var chunk = CORPUS.chunk(mapped.chunkId()).orElseThrow();
        var nonFinite = recallOf(new ResonanceResult(atom(mapped.atomId(), chunk.text()), Double.NaN));
        var unmapped = recallOf(new ResonanceResult(atom("not-in-the-manifest", chunk.text()), 0.5));
        var changed = recallOf(new ResonanceResult(atom(mapped.atomId(), chunk.text() + " changed"), 0.5));

        for (var failing : List.of(nonFinite, unmapped, changed)) {
            var capture = new SignalHitCapture(CORPUS, manifest, failing).capture(QUERY);
            assertFalse(capture.completed());
        }
        assertTrue(new SignalHitCapture(CORPUS, manifest, recallOf(new ResonanceResult(atom(mapped.atomId(),
                chunk.text()), 0.5))).capture(QUERY).completed(), "the faithful result is accepted");
    }

    @Test
    void documentFrequencyCountsChunksNotOccurrences() {
        var frequency = SignalHitCapture.documentFrequency(CORPUS);
        var expected = CORPUS.chunks().stream().filter(chunk -> HtmlTokens.distinct(chunk.text()).contains("element"))
                .count();

        assertEquals(expected, frequency.get("element").longValue());
        assertTrue(expected > 0);
    }

    private static ResonanceStoreRecall recallOf(ResonanceResult result) {
        return (query, limit, threshold) -> List.of(result);
    }

    private static KnowledgeAtom atom(String id, String content) {
        return new KnowledgeAtom(id, AtomType.values()[0], content, Map.of(), 1.0, Instant.EPOCH);
    }
}
