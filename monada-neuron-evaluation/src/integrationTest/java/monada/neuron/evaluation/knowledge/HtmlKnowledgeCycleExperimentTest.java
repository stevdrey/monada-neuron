package monada.neuron.evaluation.knowledge;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import monada.neuron.evaluation.knowledge.EvidenceTrace.Arm;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeCycleExperiment.Observation;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeCycleExperiment.Root;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeIngestion.Check;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the evidence-trace experiment in-process over two freshly imported roots; it sees query ids and texts only. */
class HtmlKnowledgeCycleExperimentTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundled();
    private static final HtmlKnowledgeCorpus REMOVED = HtmlKnowledgeEvidenceTracePhase.withoutDocument(CORPUS,
            HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT);
    private static final HtmlKnowledgeQuerySet QUERY_SET = HtmlKnowledgeQuerySet.bundled(CORPUS);

    @TempDir
    static Path directory;

    private static IngestionManifest fullManifest;
    private static Root full;
    private static Root removed;
    private static ResonanceStoreMemoryAdapter closed;
    private static List<QueryText> texts;
    private static Observation observation;

    @BeforeAll
    static void runOnce() {
        full = root("full", CORPUS);
        removed = root("removed", REMOVED);
        closed = ResonanceStoreMemoryAdapter.open(directory.resolve("full"),
                new HtmlKnowledgeRecall(CORPUS).adapterConfig());
        closed.close();
        texts = QUERY_SET.queries().stream().map(query -> new QueryText(query.queryId(), query.text())).toList();
        observation = new HtmlKnowledgeCycleExperiment().run(full, removed, closed, QUERY_SET.queryHash(), texts);
    }

    @Test
    void everySemanticCheckPasses() {
        assertEquals(List.of("evidence.rows", "evidence.recallCompleted", "evidence.requestMatchesCodec",
                "evidence.recalledEqualsB2", "evidence.traceIntegrity", "evidence.referencesResolved",
                "evidence.stageList", "evidence.termination", "evidence.deterministicReplay",
                "contrast.valueEquality", "control.memoryEmptyUnsupported", "control.removedExcludesDocument",
                "control.swappedMatchesSource", "control.tightBudgetExhausts", "control.unavailableRecall",
                "control.failurePropagates"),
                observation.checks().stream().map(Check::name).toList());
        observation.checks().forEach(check -> assertTrue(check.passed(), check.name() + ": " + check.detail()));
    }

    @Test
    void memoryArmCarriesEveryAdmittedReferenceIntoTheNextStages() {
        for (var row : observation.snapshot().rows(Arm.MEMORY)) {
            assertEquals(row.recorded().stream().map(EvidenceTrace.Hit::chunkId).toList(), row.admitted(),
                    row.queryId() + ": the roomy budget admits the whole response");
            assertEquals(row.admitted(), row.usedBySignal(), row.queryId() + ": pass-through carries every Signal");
            assertEquals(row.admitted(), row.attached(), row.queryId() + ": one evidence item per admitted result");
            assertEquals(row.admitted(), row.selected(), row.queryId() + ": the single hypothesis is selected");
            assertEquals(row.admitted().isEmpty() ? EvidenceDecision.UNSUPPORTED : EvidenceDecision.SUPPORTED,
                    row.decision(), row.queryId());
            assertFalse(row.traceAmbiguous(), row.queryId());
            assertTrue(row.contrast().isPresent(), row.queryId());
            assertEquals(!row.admitted().isEmpty(), row.contrast().get().differs(),
                    row.queryId() + ": the memory-empty run differs exactly when memory recalled something");
        }
    }

    @Test
    void everyAdapterRowRecordsItsB2Equality() {
        for (var row : observation.snapshot().rows()) {
            assertEquals(row.arm().recallsThroughAdapter() ? Optional.of(true) : Optional.empty(),
                    row.recalledEqualsB2(), row.arm() + " " + row.queryId());
        }
    }

    @Test
    void memoryPresentIsNotMemoryUsedWhenNothingIsAdmitted() {
        for (var row : observation.snapshot().rows(Arm.TIGHT_BUDGET)) {
            assertTrue(row.admitted().size() <= 1, row.queryId());
            assertEquals(EvidenceDecision.INCOMPLETE, row.decision(), row.queryId());
            assertTrue(row.attached().isEmpty(), row.queryId() + ": no stage after memory consumed the evidence");
        }
        for (var row : observation.snapshot().rows(Arm.MEMORY_EMPTY)) {
            assertEquals(EvidenceDecision.UNSUPPORTED, row.decision(), row.queryId());
            assertTrue(row.recorded().isEmpty() && row.admitted().isEmpty(), row.queryId());
        }
        for (var row : observation.snapshot().rows(Arm.UNAVAILABLE)) {
            assertEquals(EvidenceDecision.RECALL_FAILED, row.decision(), row.queryId());
        }
        for (var row : observation.snapshot().rows(Arm.THROWING)) {
            assertEquals("FAILED:MEMORY_RECALL", row.termination(), row.queryId());
            assertEquals(EvidenceDecision.INCOMPLETE, row.decision(), row.queryId());
        }
    }

    @Test
    void removedRootNeverRecallsTheRemovedDocument() {
        var slug = HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT.substring("doc:".length());
        assertTrue(CORPUS.chunks().stream().anyMatch(chunk -> chunk.chunkId().startsWith("chk:" + slug + "#")));
        for (var row : observation.snapshot().rows(Arm.REMOVED)) {
            assertTrue(row.used().stream().noneMatch(chunk -> chunk.startsWith("chk:" + slug + "#")), row.queryId());
        }
        assertEquals(CORPUS.chunks().size() - REMOVED.chunks().size(), CORPUS.chunks().stream()
                .filter(chunk -> chunk.documentId().equals(HtmlKnowledgeCycleExperiment.REMOVED_DOCUMENT)).count());
    }

    @Test
    void swappedArmSendsAnotherQueryText() {
        var rows = observation.snapshot().rows(Arm.SWAPPED);
        assertEquals(texts.size(), rows.size());
        for (var index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var input = texts.get(HtmlKnowledgeCycleExperiment.swapIndex(index, texts.size())).queryId();
            assertEquals(input, row.inputQueryId());
            assertFalse(row.queryId().equals(row.inputQueryId()), row.queryId());
            assertEquals(observation.snapshot().row(Arm.MEMORY, input).orElseThrow().admitted(), row.admitted(),
                    row.queryId() + ": the same text recalls the same chunks whatever slot it is scored against");
        }
    }

    @Test
    void snapshotRoundTripsAndRejectsTruncation() {
        var tsv = observation.snapshot().toTsv();
        assertEquals(observation.snapshot(), EvidenceTrace.Snapshot.parseTsv(tsv));
        var truncated = tsv.substring(0, tsv.lastIndexOf("end\t"));
        assertThrows(IllegalArgumentException.class, () -> EvidenceTrace.Snapshot.parseTsv(truncated));
    }

    @Test
    void anUnknownSelectedReferenceFailsClosed() {
        var experiment = new HtmlKnowledgeCycleExperiment();
        var text = QUERY_SET.queries().stream().filter(query -> query.queryId().equals("q:exact-term-01")).findFirst()
                .orElseThrow().text();
        var real = experiment.execute(full.adapter(), text, HtmlKnowledgeCycleExperiment.BUDGET);
        var result = real.calls().getFirst().response().results().getFirst();
        // A port that recalls a reference no manifest holds: the cycle carries it, the host must not resolve it.
        var forged = experiment.execute(request -> new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE,
                request.maxResults(), List.of(new ResonanceMemoryResult("rs-" + "0".repeat(32), result.signal(),
                        result.score()))), text, HtmlKnowledgeCycleExperiment.BUDGET);
        assertEquals(EvidenceDecision.SUPPORTED, experiment.decide(real, full.resolver()));
        assertEquals(EvidenceDecision.FAILED_CLOSED, experiment.decide(forged, full.resolver()));
        var unverified = new RecalledReferenceResolver(CORPUS, fullManifest,
                new HashSet<>(Set.of(full.resolver().resolve(result.reference()).chunkId().orElseThrow())));
        assertEquals(EvidenceDecision.FAILED_CLOSED, experiment.decide(real, unverified));
    }

    @Test
    void theTraceSideReferencesNoGoldLabelType() throws IOException {
        for (var type : List.of("HtmlKnowledgeCycleExperiment", "HtmlKnowledgeCycleExperiment$Run",
                "HtmlKnowledgeEvidenceTracePhase", "RecalledEvidenceReasoningStage", "RecalledReferenceResolver",
                "RecordingMemoryPort", "EvidenceTrace", "EvidenceTrace$Row", "EvidenceTrace$Snapshot",
                "EvidenceDecision")) {
            try (var input = HtmlKnowledgeCycleExperiment.class.getResourceAsStream(type + ".class")) {
                var bytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains("HtmlKnowledgeQuerySet"), type);
                assertFalse(bytes.contains("JudgedQuery"), type);
                assertFalse(bytes.contains("RecallMetrics"), type);
                assertFalse(bytes.contains("EvidenceTraceMetrics"), type);
            }
        }
    }

    private static Root root(String name, HtmlKnowledgeCorpus corpus) {
        var path = directory.resolve(name);
        var manifest = new HtmlKnowledgeIngestion(corpus).ingest(path).manifest();
        var readback = new HtmlKnowledgeIngestion(corpus)
                .readbackSweep(MonadaMemory.open(path, MonadaMemoryOptions.defaults()), manifest);
        if (name.equals("full")) {
            fullManifest = manifest;
        }
        return new Root(name, ResonanceStoreMemoryAdapter.open(path, new HtmlKnowledgeRecall(corpus).adapterConfig()),
                new RecalledReferenceResolver(corpus, manifest, new HashSet<>(readback.unread())));
    }
}
