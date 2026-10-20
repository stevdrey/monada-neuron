package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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
    void aSubstitutionIsATieOnlyWhenTheTieStraddlesTheCutoff() {
        var a = new Row("q", Layer.B1, 5, 1, "a", 0.5);
        var b = new Row("q", Layer.B1, 5, 2, "b", 0.5);
        var c = new Row("q", Layer.B1, 5, 3, "c", 0.5);
        var swappedA = new Row("q", Layer.B1, 3, 1, "b", 0.5);
        var swappedB = new Row("q", Layer.B1, 3, 2, "a", 0.5);

        var noRowBeyond = HtmlKnowledgeRecall.prefixDifferences(List.of(a, b), List.of(swappedA, swappedB), 3);
        assertEquals(2, noRowBeyond.size());
        assertTrue(noRowBeyond.stream().noneMatch(HtmlKnowledgeRecall.PrefixDifference::straddlingTie),
                "two results returned for limit 3: no tie can straddle the cutoff");
        assertTrue(noRowBeyond.getFirst().detail().contains("no tie straddles the cutoff"));

        var straddling = HtmlKnowledgeRecall.prefixDifferences(List.of(a, b, c), List.of(swappedA, swappedB), 2);
        assertTrue(straddling.stream().allMatch(HtmlKnowledgeRecall.PrefixDifference::straddlingTie),
                "rank 3 shares the cutoff score, so the tie straddles cutoff 2");

        var lower = new Row("q", Layer.B1, 5, 3, "c", 0.1);
        var differentScore = HtmlKnowledgeRecall.prefixDifferences(List.of(a, b, lower),
                List.of(new Row("q", Layer.B1, 2, 1, "c", 0.1), swappedB), 2);
        assertTrue(differentScore.stream().noneMatch(HtmlKnowledgeRecall.PrefixDifference::straddlingTie));
        assertEquals(List.of(), HtmlKnowledgeRecall.prefixDifferences(List.of(a, b, c), List.of(a, b), 2));
        assertEquals(1, HtmlKnowledgeRecall.prefixDifferences(List.of(a, b, c), List.of(a), 2).size(), "length");
    }

    @Test
    void theColdSampleIsTheFirstCallOnFreshHandlesAndNeverABlankQuery() {
        var recall = new HtmlKnowledgeRecall(CORPUS);
        var memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
        var calls = new ArrayList<String>();
        ResonanceStoreRecall recording = (query, limit, threshold) -> {
            calls.add(query);
            return memory.resonate(query).topK(limit).threshold(threshold).execute().results();
        };
        var adapter = ResonanceStoreMemoryAdapter.using(recording, recall.adapterConfig());
        var queries = texts(QUERY_SET);
        var sample = HtmlKnowledgeRecall.firstWithToken(queries).orElseThrow();

        assertTrue(queries.getFirst().text().isBlank(), "blank queries sort first");
        assertFalse(sample.text().isBlank());
        var cold = recall.coldFirstRecall(memory, adapter, sample);

        assertTrue(cold.b1().isPresent() && cold.b2().isPresent());
        assertEquals(List.of(), cold.failures());
        assertEquals(List.copyOf(HtmlTokens.distinct(sample.text())), calls,
                "the adapter's first calls are exactly the cold sample's token queries");
        assertTrue(HtmlKnowledgeRecall.firstWithToken(List.of(new QueryText("b", "  "))).isEmpty());
    }

    @Test
    void aFailedOrIncompleteColdCallIsReportedAndNeverASample() {
        var recall = new HtmlKnowledgeRecall(CORPUS);
        var memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
        var sample = HtmlKnowledgeRecall.firstWithToken(texts(QUERY_SET)).orElseThrow();
        var calls = new int[1];
        ResonanceStoreRecall failingFirst = (query, limit, threshold) -> {
            if (calls[0]++ == 0) {
                throw new UncheckedIOException(new IOException("injected first-call failure"));
            }
            return memory.resonate(query).topK(limit).threshold(threshold).execute().results();
        };
        var adapter = ResonanceStoreMemoryAdapter.using(failingFirst, recall.adapterConfig());

        var cold = recall.coldFirstRecall(memory, adapter, sample);
        assertTrue(cold.b1().isPresent(), "B1 succeeded");
        assertTrue(cold.b2().isEmpty(), "a FAILED B2 response is not a sample");
        assertTrue(cold.failures().getFirst().contains("B2 answered FAILED"), cold.failures().toString());

        var observed = recall.observe(memory, adapter, manifest, "probe", List.of(sample));
        assertTrue(observed.checks().stream().allMatch(HtmlKnowledgeIngestion.Check::passed),
                "a recovered latency-only failure never decides the verdict");
        assertEquals("0", observed.metadata().get("perf.warmup.failures"));

        // A warm-up failure on calls made only for latency is a diagnostic; the measured recalls judge the run.
        var warmupCalls = new int[1];
        ResonanceStoreRecall failingWarmup = (query, limit, threshold) -> {
            if (limit == HtmlKnowledgeRecall.MAX_LIMIT && warmupCalls[0]++ == 0) {
                throw new UncheckedIOException(new IOException("injected warm-up failure"));
            }
            return memory.resonate(query).topK(limit).threshold(threshold).execute().results();
        };
        var flaky = ResonanceStoreMemoryAdapter.using(failingWarmup, recall.adapterConfig());
        var warmed = recall.observe(memory, flaky, manifest, "probe", List.of(sample));
        assertTrue(warmed.checks().stream().allMatch(HtmlKnowledgeIngestion.Check::passed), warmed.checks().toString());
        assertTrue(warmed.metadata().get("perf.warmup.failures").startsWith("1: warm-up " + sample.queryId()
                + " B2 answered FAILED"), warmed.metadata().get("perf.warmup.failures"));
        assertEquals("unavailable (no successful sample)", metadataOf(new LatencyRow(new long[0], 0, 1, "x", false,
                "none")).get("r.medianNanos"));
        assertEquals("2", metadataOf(new LatencyRow(new long[] {5, 7}, 0, 1, "x", false, "none"))
                .get("r.measurementIterations"));
    }

    private static Map<String, String> metadataOf(LatencyRow row) {
        var metadata = new TreeMap<String, String>();
        row.describe("r", metadata);
        return metadata;
    }

    @Test
    void aFailedMeasuredCallIsNeverALatencySample() {
        var recall = new HtmlKnowledgeRecall(CORPUS);
        var memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
        var fieldsetCalls = new int[1];
        ResonanceStoreRecall failingMeasured = (query, limit, threshold) -> {
            // The second limit-5 call for "fieldset" is the measured one (the first is the warm-up's).
            if (query.equals("fieldset") && limit == HtmlKnowledgeRecall.MAX_LIMIT && ++fieldsetCalls[0] == 2) {
                throw new UncheckedIOException(new IOException("injected measured failure"));
            }
            return memory.resonate(query).topK(limit).threshold(threshold).execute().results();
        };
        var adapter = ResonanceStoreMemoryAdapter.using(failingMeasured, recall.adapterConfig());

        var observed = recall.observe(memory, adapter, manifest, "probe",
                List.of(new QueryText("a", "fieldset"), new QueryText("b", "legend")));

        assertEquals("1", observed.metadata().get("perf.b2.recallLimit5.measurementIterations"),
                "the failed measured B2 call is excluded; " + observed.metadata());
        assertEquals("2", observed.metadata().get("perf.b1.recallLimit5.measurementIterations"));
        assertFalse(observed.checks().stream().filter(check -> check.name().equals("recall.completed")).findFirst()
                .orElseThrow().passed());
    }

    @Test
    void aFidelityFailedMeasuredCallIsNeverALatencySample() {
        var recall = new HtmlKnowledgeRecall(CORPUS);
        var memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
        try (var adapter = ResonanceStoreMemoryAdapter.open(root, recall.adapterConfig())) {
            var observed = recall.observe(memory, adapter, swapped(), "swapped",
                    List.of(new QueryText("swapped", CORPUS.chunks().get(0).text())));

            assertFalse(observed.checks().stream().filter(check -> check.name().equals("fidelity.source"))
                    .findFirst().orElseThrow().passed());
            assertEquals("0", observed.metadata().get("perf.b1.recallLimit5.measurementIterations"),
                    "the measured B1 call returned but failed source fidelity; " + observed.metadata());
            assertTrue(observed.metadata().get("perf.b1.recallLimit5.excludedSamples")
                    .contains("swapped failed source fidelity (see fidelity.source)"), observed.metadata().toString());
        }
    }

    @Test
    void theWorkloadBoundCountsUtf8BytesNotCharacters() {
        // Two UTF-8 bytes per char: under the bound in chars, over it in bytes.
        var accented = QueryText.renderTsv("h", List.of(new QueryText("q", "é".repeat(HtmlKnowledgeRecall
                .MAX_WORKLOAD_BYTES / 2 + 1))));
        assertTrue(accented.length() <= HtmlKnowledgeRecall.MAX_WORKLOAD_BYTES);
        var thrown = assertThrows(IngestionInputException.class, () -> QueryText.parseTsv(accented));
        assertTrue(thrown.getMessage().contains("UTF-8 bytes"), thrown.getMessage());

        var ascii = QueryText.renderTsv("h", List.of(new QueryText("q", "e".repeat(HtmlKnowledgeRecall
                .MAX_WORKLOAD_BYTES / 2 + 1))));
        assertEquals(1, QueryText.parseTsv(ascii).queries().size());
    }

    @Test
    void scoresAreComparedBitExactly() {
        assertTrue(HtmlKnowledgeRecall.sameScore(0.5, 0.5));
        assertFalse(HtmlKnowledgeRecall.sameScore(0.0, -0.0), "signed zeros are different bits");
        var positive = new Row("q", Layer.B1, 5, 1, "a", 0.0);
        var negative = new Row("q", Layer.B1, 3, 1, "a", -0.0);
        var differences = HtmlKnowledgeRecall.prefixDifferences(List.of(positive), List.of(negative), 3);
        assertEquals(1, differences.size(), "the same chunk with -0.0 instead of +0.0 is a difference");
        assertFalse(differences.getFirst().straddlingTie());
        assertFalse(RecallMetrics.tieAtCutoff(List.of(0.0, -0.0), 1), "+0.0 and -0.0 are no tie");
    }

    @Test
    void aNonFiniteStoreScoreIsNeverAResult() {
        var atom = new KnowledgeAtom("atom", AtomType.values()[0], "text", Map.of(), 1.0, Instant.EPOCH);

        assertTrue(HtmlKnowledgeRecall.allFinite(List.of(new ResonanceResult(atom, 0.5))));
        assertFalse(HtmlKnowledgeRecall.allFinite(List.of(new ResonanceResult(atom, 0.5),
                new ResonanceResult(atom, Double.NaN))));
        assertFalse(HtmlKnowledgeRecall.allFinite(List.of(new ResonanceResult(atom, Double.POSITIVE_INFINITY))));
        assertEquals(Optional.of("returned a non-finite score"),
                HtmlKnowledgeRecall.rejectStoreResults(List.of(new ResonanceResult(atom, Double.NaN))),
                "the one rule shared by the measured, cold and warm-up B1 paths and by B2c");
        assertEquals(Optional.empty(), HtmlKnowledgeRecall.rejectStoreResults(List.of(new ResonanceResult(atom, 1.0))));
        assertEquals("null", HtmlKnowledgeRecallReport.number(Double.NaN));
        assertEquals("null", HtmlKnowledgeRecallReport.number(Double.NEGATIVE_INFINITY));
        assertEquals("0.25", HtmlKnowledgeRecallReport.number(0.25));
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

    @Test
    void aFailedTimingOpenIsAnExcludedSampleAndTheObservationUsesItsOwnHandles() {
        var recall = new HtmlKnowledgeRecall(CORPUS);
        var opens = new int[1];
        HtmlKnowledgeRecall.HandleOpener opener = () -> {
            if (++opens[0] == 2) {
                throw new IllegalStateException("injected open failure");
            }
            return new HtmlKnowledgeRecall.Handles(MonadaMemory.open(root, MonadaMemoryOptions.defaults()),
                    ResonanceStoreMemoryAdapter.open(root, recall.adapterConfig()));
        };

        var observed = recall.observe(opener, manifest, QUERY_SET.queryHash(), texts(QUERY_SET));

        assertEquals(LatencyRow.REPETITIONS + 1, opens[0], "five timed pairs plus the observation's own pair");
        assertTrue(observed.checks().stream().allMatch(HtmlKnowledgeIngestion.Check::passed), observed.checks().toString());
        assertEquals(observation.snapshot(), observed.snapshot(), "a timing failure never changes the results");
        assertEquals("4", observed.metadata().get("perf.open.measurementIterations"));
        assertTrue(observed.metadata().get("perf.open.excludedSamples").startsWith("1: repetition 2 threw"));
        assertEquals("4", observed.metadata().get("perf.b2.firstRecall.measurementIterations"));
    }

    @Test
    void onlyTheFirstReadbackIsRequiredAndLaterExceptionsAreExcludedSamples() {
        var passed = List.of(new HtmlKnowledgeIngestion.Check("persistence.readback", true, "ok"));
        var calls = new int[1];
        var sampled = HtmlKnowledgeRecallPhase.sampleReadbacks(() -> {
            if (++calls[0] == 3) {
                throw new IllegalStateException("injected repetition failure");
            }
            return passed;
        }, 33);
        var metadata = new TreeMap<String, String>();
        sampled.row().describe("r", metadata);

        assertEquals(passed, sampled.checks());
        assertEquals("4", metadata.get("r.measurementIterations"));
        assertTrue(metadata.get("r.excludedSamples").startsWith("1: repetition 3 threw"), metadata.get("r.excludedSamples"));
        assertThrows(IllegalStateException.class, () -> HtmlKnowledgeRecallPhase.sampleReadbacks(() -> {
            throw new IllegalStateException("the required readback");
        }, 33), "an exception from the required readback still fails the phase");
    }
}
