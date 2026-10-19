package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Row;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Snapshot;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;
import monada.neuron.resonance.adapter.ResonanceStoreRecall;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
                texts(QUERY_SET));
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
    void everySystemIsRecordedAtEveryLimitAndB2EqualsB2cAtEach() {
        var snapshot = observation.snapshot();
        for (var query : QUERY_SET.queries()) {
            if (query.text().isBlank()) {
                continue;
            }
            for (var layer : Layer.values()) {
                var lookahead = snapshot.rows(query.queryId(), layer, 6).size();
                assertTrue(lookahead > 0, query.queryId() + " " + layer + " returned nothing at limit 6");
                for (var limit : List.of(1, 3, 5)) {
                    assertEquals(Math.min(limit, lookahead), snapshot.rows(query.queryId(), layer, limit).size(),
                            query.queryId() + " " + layer + " limit " + limit);
                }
            }
            for (var limit : List.of(1, 3, 5, 6)) {
                assertEquals(snapshot.rows(query.queryId(), Layer.B2, limit).stream().map(Row::chunkId).toList(),
                        snapshot.rows(query.queryId(), Layer.B2C, limit).stream().map(Row::chunkId).toList(),
                        query.queryId() + " limit " + limit);
            }
        }
    }

    @Test
    void theReopenReadbackReadsEveryChunkBackAndDetectsMisattribution() {
        var recall = new HtmlKnowledgeRecall(CORPUS);
        recall.readback(root, manifest).forEach(check -> assertTrue(check.passed(), check.name() + ": " + check.detail()));

        var readback = recall.readback(root, swapped()).stream()
                .filter(check -> check.name().equals("persistence.readback")).findFirst().orElseThrow();
        assertFalse(readback.passed());
        assertTrue(readback.detail().contains(CORPUS.chunks().get(0).chunkId()), readback.detail());
        assertTrue(readback.detail().contains(CORPUS.chunks().get(1).chunkId()), readback.detail());
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
                texts(QUERY_SET));

        assertEquals(observation.snapshot(), again.snapshot());
        assertEquals(observation.snapshot(), Snapshot.parseTsv(observation.snapshot().toTsv()));
    }

    @Test
    void aManifestThatMisattributesAtomsFailsSourceFidelity() {
        var observed = new HtmlKnowledgeRecall(CORPUS).observe(root, swapped(), "swapped",
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

    @Test
    void lookaheadRowsAreRecordedButNeverScoredAsResults() {
        var snapshot = observation.snapshot();
        for (var query : QUERY_SET.queries()) {
            for (var layer : Layer.values()) {
                var scored = snapshot.rows(query.queryId(), layer, HtmlKnowledgeRecall.MAX_LIMIT);
                var lookahead = snapshot.rows(query.queryId(), layer, HtmlKnowledgeRecall.LOOKAHEAD_LIMIT);
                assertTrue(lookahead.size() <= HtmlKnowledgeRecall.LOOKAHEAD_LIMIT);
                assertEquals(scored.stream().map(Row::score).toList(),
                        lookahead.stream().limit(scored.size()).map(Row::score).toList(), query.queryId() + " " + layer);
            }
        }
        // B0's integer scores tie widely: "the" occurs in more than six chunks.
        var ties = new HtmlKnowledgeRecall(CORPUS).observe(root, manifest, "probe", List.of(new QueryText("probe", "the")));
        var lookahead = ties.snapshot().rows("probe", Layer.B0, HtmlKnowledgeRecall.LOOKAHEAD_LIMIT);
        assertEquals(6, lookahead.size());
        assertEquals(lookahead.get(4).score(), lookahead.get(5).score());
    }

    @Test
    void aFailureAtAnyLimitLeavesTheQueryUnscoredInThatLayer() {
        var memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
        ResonanceStoreRecall failingAtThree = (query, limit, threshold) -> {
            if (limit == 3) {
                throw new UncheckedIOException(new IOException("injected limit-3 failure"));
            }
            return memory.resonate(query).topK(limit).threshold(threshold).execute().results();
        };
        var recall = new HtmlKnowledgeRecall(CORPUS);
        var adapter = ResonanceStoreMemoryAdapter.using(failingAtThree, recall.adapterConfig());
        var observed = recall.observe(memory, adapter, manifest, "probe",
                List.of(new QueryText("probe", "What does the fieldset element do?")));

        var completed = observed.checks().stream().filter(check -> check.name().equals("recall.completed"))
                .findFirst().orElseThrow();
        assertFalse(completed.passed());
        assertTrue(completed.detail().contains("probe B2 limit 3: answered FAILED"), completed.detail());
        var b2 = observed.snapshot().rows("probe", Layer.B2, HtmlKnowledgeRecall.MAX_LIMIT);
        assertTrue(b2.stream().anyMatch(row -> row.chunkId().equals(HtmlKnowledgeRecall.FAILED)), b2.toString());
        assertEquals(1, b2.stream().filter(row -> row.chunkId().equals(HtmlKnowledgeRecall.FAILED)).count());
        assertTrue(observed.snapshot().rows("probe", Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT).stream()
                .noneMatch(row -> row.chunkId().equals(HtmlKnowledgeRecall.FAILED)), "B1 did not fail");
        for (var name : List.of("neuron.b2EqualsB2c", "recall.prefixStability")) {
            var check = observed.checks().stream().filter(value -> value.name().equals(name)).findFirst()
                    .orElseThrow();
            assertFalse(check.passed(), name + " must not pass with a skipped comparison");
            assertTrue(check.detail().contains("probe") && check.detail().contains("limit 3: not compared"),
                    check.detail());
        }
    }

    @Test
    void theWorkloadCarriesIdsAndTextsOnly() {
        var texts = texts(QUERY_SET);
        var tsv = QueryText.renderTsv(QUERY_SET.queryHash(), texts);
        var workload = QueryText.parseTsv(tsv);

        assertEquals(QUERY_SET.queryHash(), workload.queryHash());
        assertEquals(texts, workload.queries());
        for (var query : QUERY_SET.queries()) {
            for (var chunkId : query.relevant()) {
                assertFalse(tsv.contains(chunkId), "workload leaks " + chunkId);
            }
            query.keyClaims().forEach(claim -> assertFalse(tsv.contains(claim), "workload leaks " + claim));
        }
        assertThrows(IngestionInputException.class, () -> QueryText.parseTsv("queryId\ttext\n"));
        assertThrows(IngestionInputException.class,
                () -> QueryText.parseTsv("# queryHash x\nqueryId\ttext\nq\ta\nq\tb\nend\t2\n"), "duplicate id");
        assertThrows(IngestionInputException.class,
                () -> QueryText.parseTsv(tsv.substring(0, tsv.indexOf("q:paraphrase-01"))), "truncated at a row");
        var snapshot = observation.snapshot().toTsv();
        assertThrows(IllegalArgumentException.class,
                () -> Snapshot.parseTsv(snapshot.substring(0, snapshot.indexOf("\nq:paraphrase-01") + 1)));
    }

    @Test
    void theRecallSideReferencesNoGoldLabelType() throws IOException {
        for (var type : List.of("HtmlKnowledgeRecallPhase", "HtmlKnowledgeRecall", "HtmlKnowledgeRecall$Run",
                "HtmlKnowledgeRecall$QueryText", "HtmlKnowledgeRecall$QueryText$Workload", "LexicalOverlapBaseline",
                "TokenPackingSignalCodec")) {
            try (var input = HtmlKnowledgeRecall.class.getResourceAsStream(type + ".class")) {
                var bytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains("HtmlKnowledgeQuerySet"), type);
                assertFalse(bytes.contains("JudgedQuery"), type);
                assertFalse(bytes.contains("RecallMetrics"), type);
            }
        }
    }

    /** Returns the import manifest with the atoms of the first two chunks swapped. */
    private static IngestionManifest swapped() {
        var entries = new ArrayList<>(manifest.entries());
        var first = entries.get(0);
        var second = entries.get(1);
        entries.set(0, new IngestionManifest.Entry(first.chunkId(), second.atomId(), second.opaqueReference()));
        entries.set(1, new IngestionManifest.Entry(second.chunkId(), first.atomId(), first.opaqueReference()));
        return new IngestionManifest(CORPUS.version(), CORPUS.corpusHash(), entries);
    }

    private static List<QueryText> texts(HtmlKnowledgeQuerySet querySet) {
        return querySet.queries().stream().map(query -> new QueryText(query.queryId(), query.text())).toList();
    }

    private static List<String> chunkIds(Snapshot snapshot, String queryId, Layer layer) {
        return snapshot.rows(queryId, layer, HtmlKnowledgeRecall.MAX_LIMIT).stream().map(Row::chunkId).toList();
    }
}
