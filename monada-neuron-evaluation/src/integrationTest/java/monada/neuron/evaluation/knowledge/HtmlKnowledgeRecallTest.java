package monada.neuron.evaluation.knowledge;

import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observes one imported root; the recall side sees query ids and texts only. */
class HtmlKnowledgeRecallTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundled();
    private static final HtmlKnowledgeQuerySet QUERY_SET = HtmlKnowledgeQuerySet.bundled(CORPUS);

    @TempDir
    static Path directory;

    private static Path root;
    private static IngestionManifest manifest;
    private static HtmlKnowledgeRecall.Observation observation;

    @BeforeAll
    static void observeOnce() {
        root = directory.resolve("store");
        manifest = new HtmlKnowledgeIngestion(CORPUS).ingest(root).manifest();
        observation = new HtmlKnowledgeRecall(CORPUS).observe(root, manifest, QUERY_SET.queryHash(),
                QueryText.of(QUERY_SET));
    }

    @Test
    void everySemanticCheckPasses() {
        assertEquals(List.of("recall.completed", "fidelity.source", "fidelity.reference", "neuron.b2EqualsB2c",
                "recall.prefixStability"), observation.checks().stream().map(HtmlKnowledgeIngestion.Check::name).toList());
        observation.checks().forEach(check -> assertTrue(check.passed(), check.name() + ": " + check.detail()));
    }

    @Test
    void everyQueryIsRecordedForEveryLayerWithinTheLimit() {
        var snapshot = observation.snapshot();
        for (var query : QUERY_SET.queries()) {
            for (var layer : Layer.values()) {
                var rows = snapshot.rows(query.queryId(), layer, HtmlKnowledgeRecall.MAX_LIMIT);
                assertTrue(rows.size() <= HtmlKnowledgeRecall.MAX_LIMIT, query.queryId());
                rows.forEach(row -> assertTrue(CORPUS.chunk(row.chunkId()).isPresent(), row.toString()));
                assertEquals(rows.stream().map(Row::rank).toList(),
                        IntStream.rangeClosed(1, rows.size()).boxed().toList());
            }
            assertEquals(chunkIds(snapshot, query.queryId(), Layer.B2), chunkIds(snapshot, query.queryId(), Layer.B2C));
        }
    }

    @Test
    void blankQueriesTravelThroughThePortAndReturnNothing() {
        for (var query : QUERY_SET.byCategory().get(JudgedQuery.Category.BLANK)) {
            for (var layer : Layer.values()) {
                assertEquals(List.of(), observation.snapshot().rows(query.queryId(), layer,
                        HtmlKnowledgeRecall.MAX_LIMIT), query.queryId() + " " + layer);
            }
        }
    }

    @Test
    void exactChunkTextIsRecalledFirstByTheStore() {
        var chunk = CORPUS.chunks().getFirst();
        var probe = new HtmlKnowledgeRecall(CORPUS).observe(root, manifest, "probe",
                List.of(new QueryText("probe", chunk.text())));

        assertEquals(chunk.chunkId(), probe.snapshot().rows("probe", Layer.B1, 5).getFirst().chunkId());
    }

    @Test
    void repeatedObservationThroughFreshHandlesIsIdentical() {
        var again = new HtmlKnowledgeRecall(CORPUS).observe(root, manifest, QUERY_SET.queryHash(),
                QueryText.of(QUERY_SET));

        assertEquals(observation.snapshot(), again.snapshot());
        assertEquals(observation.snapshot(), Snapshot.parseTsv(observation.snapshot().toTsv()));
    }

    @Test
    void aManifestThatMisattributesAtomsFailsSourceFidelity() {
        var entries = new ArrayList<>(manifest.entries());
        var first = entries.get(0);
        var second = entries.get(1);
        entries.set(0, new IngestionManifest.Entry(first.chunkId(), second.atomId(), second.opaqueReference()));
        entries.set(1, new IngestionManifest.Entry(second.chunkId(), first.atomId(), first.opaqueReference()));
        var swapped = new IngestionManifest(CORPUS.version(), CORPUS.corpusHash(), entries);

        var observed = new HtmlKnowledgeRecall(CORPUS).observe(root, swapped, "swapped",
                List.of(new QueryText("swapped", CORPUS.chunks().get(0).text())));

        var fidelity = observed.checks().stream().filter(check -> check.name().equals("fidelity.source"))
                .findFirst().orElseThrow();
        assertFalse(fidelity.passed());
        assertTrue(fidelity.detail().contains("does not hash to its corpus sha256"), fidelity.detail());
    }

    @Test
    void aRootWithoutAStoreIsRejectedInsteadOfCreated() {
        assertThrows(IngestionInputException.class, () -> new HtmlKnowledgeRecall(CORPUS)
                .observe(directory.resolve("missing"), manifest, "x", List.of(new QueryText("x", "a"))));
    }

    private static List<String> chunkIds(Snapshot snapshot, String queryId, Layer layer) {
        return snapshot.rows(queryId, layer, HtmlKnowledgeRecall.MAX_LIMIT).stream().map(Row::chunkId).toList();
    }
}
