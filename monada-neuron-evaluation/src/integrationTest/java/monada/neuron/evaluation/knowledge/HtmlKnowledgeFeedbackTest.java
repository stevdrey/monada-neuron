package monada.neuron.evaluation.knowledge;

import com.monada.storage.feedback.FeedbackSignal;
import monada.neuron.evaluation.knowledge.FeedbackEvents.Event;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.Layer;
import monada.neuron.evaluation.knowledge.HtmlKnowledgeRecall.QueryText;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Imports every arm once into a temporary directory and exercises the phase side in process. */
class HtmlKnowledgeFeedbackTest {

    private static final HtmlKnowledgeCorpus CORPUS = HtmlKnowledgeCorpus.bundled();
    private static final String TEXT = "What does the fieldset element do?";
    private static final List<QueryText> WORKLOAD = List.of(new QueryText("q:a", TEXT),
            new QueryText("q:b", "What does the canvas element draw?"), new QueryText("q:blank", ""));

    @TempDir
    static Path directory;

    private static Path arms;
    private static HtmlKnowledgeFeedback feedback;
    private static final Map<FeedbackArm, IngestionManifest> MANIFESTS = new EnumMap<>(FeedbackArm.class);

    @BeforeAll
    static void importArms() {
        arms = directory.resolve("arms");
        feedback = new HtmlKnowledgeFeedback(CORPUS);
        for (var arm : FeedbackArm.values()) {
            var imported = feedback.ingest(arms, arm);
            assertTrue(imported.allPassed(), imported.checks().toString());
            MANIFESTS.put(arm, imported.manifest());
        }
    }

    @Test
    void everyArmRootOpensWithEveryKeyStrategyAndRankingFlag() {
        var check = feedback.strategyNeutral(arms);
        assertTrue(check.passed(), check.detail());
    }

    @Test
    void aMissingArmRootFailsStrategyNeutralityWithoutBeingCreated() throws IOException {
        var copy = copyArms("missing");
        var root = HtmlKnowledgeFeedback.root(copy, FeedbackArm.TREATMENT_ENRICHED);
        HtmlKnowledgeIngestionRunner.deleteTree(root);
        var check = feedback.strategyNeutral(copy);
        assertFalse(check.passed());
        assertTrue(check.detail().contains(FeedbackArm.TREATMENT_ENRICHED.label() + ": no Store"), check.detail());
        assertFalse(Files.exists(root), "the check must not create a store on a missing root");
    }

    @Test
    void persistedNamesOnlyTheMissingEventInsteadOfShiftingTheRest() {
        var arm = FeedbackArm.TREATMENT_NORMALIZED;
        var chunks = MANIFESTS.get(arm).entries().stream().limit(3).map(IngestionManifest.Entry::chunkId).toList();
        var plan = new FeedbackEvents(chunks.stream().map(chunk -> new Event("q:a", TEXT, chunk,
                FeedbackSignal.NEGATIVE)).toList());
        var stored = plan.events().stream().map(event -> new FeedbackEventLog.StoredEvent(event.text(),
                arm.strategy().effectiveKey(event.text()), event.chunkId(), "rs-x", event.signal(),
                HtmlKnowledgeFeedbackRunner.NEGATIVE_DELTA)).toList();
        var complete = HtmlKnowledgeFeedbackRunner.persisted(arm, plan,
                Optional.of(new FeedbackEventLog(stored, "sha", 1)));
        assertTrue(complete.passed(), complete.detail());
        var withoutFirst = HtmlKnowledgeFeedbackRunner.persisted(arm, plan,
                Optional.of(new FeedbackEventLog(stored.subList(1, stored.size()), "sha", 1)));
        assertFalse(withoutFirst.passed());
        assertEquals("1 planned events not stored: q:a " + plan.events().getFirst().chunkId() + " NEGATIVE",
                withoutFirst.detail());
        var reversed = HtmlKnowledgeFeedbackRunner.persisted(arm, plan,
                Optional.of(new FeedbackEventLog(stored.reversed(), "sha", 1)));
        assertEquals("stored out of plan order", reversed.detail());
    }

    @Test
    void theLogIsReadFromTheSegmentTheManifestRecords() throws IOException {
        var copy = copyArms("segment");
        var arm = FeedbackArm.TREATMENT_EXACT;
        var root = HtmlKnowledgeFeedback.root(copy, arm);
        var chunk = MANIFESTS.get(arm).entries().getFirst().chunkId();
        assertTrue(feedback.apply(copy, arm, MANIFESTS.get(arm), new FeedbackEvents(List.of(
                new Event("q:a", TEXT, chunk, FeedbackSignal.POSITIVE))), new ArrayList<>()).passed());
        var manifest = root.resolve("manifest.json");
        var json = Files.readString(manifest);
        var segment = FeedbackEventLog.segment(root);
        assertTrue(json.contains("\"" + segment + "\""), json);
        Files.move(root.resolve(segment), root.resolve("feedback/moved.log"));
        Files.writeString(manifest, json.replace("\"" + segment + "\"", "\"feedback/moved.log\""));
        assertEquals("feedback/moved.log", FeedbackEventLog.segment(root));
        var log = FeedbackEventLog.read(root, MANIFESTS.get(arm));
        assertEquals(1, log.events().size());
        assertEquals(chunk, log.events().getFirst().chunkId());
    }

    @Test
    void feedbackIsPersistedWithEachStrategysKeyAndReadBackWithoutAtomIds() throws IOException {
        var copy = copyArms("persisted");
        var chunk = MANIFESTS.get(FeedbackArm.CONTROL).entries().getFirst().chunkId();
        var plan = new FeedbackEvents(List.of(new Event("q:a", TEXT, chunk, FeedbackSignal.POSITIVE)));
        for (var arm : FeedbackArm.treatments()) {
            var durations = new ArrayList<Long>();
            var applied = feedback.apply(copy, arm, MANIFESTS.get(arm), plan, durations);
            assertTrue(applied.passed(), applied.detail());
            assertEquals(1, durations.size());
            var log = FeedbackEventLog.read(HtmlKnowledgeFeedback.root(copy, arm), MANIFESTS.get(arm));
            assertEquals(1, log.events().size());
            var stored = log.events().getFirst();
            assertEquals(TEXT, stored.query());
            assertEquals(arm.strategy().effectiveKey(TEXT), stored.queryKey());
            assertEquals(chunk, stored.chunkId());
            assertTrue(HtmlKnowledgeRecall.sameScore(HtmlKnowledgeFeedbackRunner.POSITIVE_DELTA, stored.delta()));
            assertEquals(log, FeedbackEventLog.parseTsv(log.toTsv()));
            var atomId = MANIFESTS.get(arm).entryForChunk(chunk).orElseThrow().atomId();
            assertFalse(log.toTsv().contains(atomId));
        }
        assertNotEquals(FeedbackArm.TREATMENT_EXACT.strategy().effectiveKey(TEXT),
                FeedbackArm.TREATMENT_NORMALIZED.strategy().effectiveKey(TEXT));
        var control = FeedbackEventLog.read(HtmlKnowledgeFeedback.root(copy, FeedbackArm.CONTROL),
                MANIFESTS.get(FeedbackArm.CONTROL));
        assertTrue(control.events().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> feedback.apply(copy, FeedbackArm.CONTROL,
                MANIFESTS.get(FeedbackArm.CONTROL), plan, new ArrayList<>()));
    }

    @Test
    void anUnknownChunkFailsTheAppliedCheckAndWritesNothing() throws IOException {
        var copy = copyArms("unknown");
        var plan = new FeedbackEvents(List.of(new Event("q:a", TEXT, "chk:missing#00", FeedbackSignal.POSITIVE)));
        var applied = feedback.apply(copy, FeedbackArm.TREATMENT_EXACT, MANIFESTS.get(FeedbackArm.TREATMENT_EXACT),
                plan, new ArrayList<>());
        assertFalse(applied.passed());
        assertTrue(FeedbackEventLog.read(HtmlKnowledgeFeedback.root(copy, FeedbackArm.TREATMENT_EXACT),
                MANIFESTS.get(FeedbackArm.TREATMENT_EXACT)).events().isEmpty());
    }

    @Test
    void readingARootWithoutAFeedbackLogNeverCreatesOne() throws IOException {
        var copy = copyArms("nolog");
        var root = HtmlKnowledgeFeedback.root(copy, FeedbackArm.CONTROL);
        Files.delete(FeedbackEventLog.logFile(root));
        assertThrows(IngestionInputException.class, () -> FeedbackEventLog.read(root,
                MANIFESTS.get(FeedbackArm.CONTROL)));
        assertFalse(Files.exists(FeedbackEventLog.logFile(root)));
    }

    @Test
    void feedbackChangesOnlyItsOwnKeyAndDisablingRankingRestoresTheControl() throws IOException {
        var copy = copyArms("effect");
        var control = feedback.observe(copy, FeedbackArm.CONTROL, true, "control",
                MANIFESTS.get(FeedbackArm.CONTROL), "hash", WORKLOAD, false).observation().snapshot();
        var top = control.rows("q:a", Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT).getLast().chunkId();
        var plan = new FeedbackEvents(List.of(new Event("q:a", TEXT, top, FeedbackSignal.POSITIVE)));
        var arm = FeedbackArm.TREATMENT_EXACT;
        assertTrue(feedback.apply(copy, arm, MANIFESTS.get(arm), plan, new ArrayList<>()).passed());
        var treated = feedback.observe(copy, arm, true, arm.label(), MANIFESTS.get(arm), "hash", WORKLOAD, true);
        var treatment = treated.observation().snapshot();
        assertNotEquals(control.rows("q:a", Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT),
                treatment.rows("q:a", Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT));
        assertEquals(control.rows("q:b", Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT),
                treatment.rows("q:b", Layer.B1, HtmlKnowledgeRecall.MAX_LIMIT));
        assertTrue(treated.checks().stream().noneMatch(check -> check.name().endsWith(
                HtmlKnowledgeFeedback.PREFIX_STABILITY)));
        assertTrue(treated.metadata().containsKey("diag." + arm.label() + ".prefixStability"));
        var disabled = feedback.observe(copy, arm, false, arm.label() + "-disabled", MANIFESTS.get(arm), "hash",
                WORKLOAD, false).observation().snapshot();
        assertEquals(control, disabled);
    }

    @Test
    void thePhaseSideNeverReferencesGoldLabels() throws IOException {
        for (var type : List.of("HtmlKnowledgeFeedbackPhase", "HtmlKnowledgeFeedback", "HtmlKnowledgeFeedback$Observed",
                "FeedbackEvents", "FeedbackEvents$Event", "FeedbackEventLog", "FeedbackEventLog$StoredEvent",
                "FeedbackArm", "FeedbackArm$KeyStrategy")) {
            try (var input = HtmlKnowledgeFeedback.class.getResourceAsStream(type + ".class")) {
                var bytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (var forbidden : List.of("HtmlKnowledgeQuerySet", "JudgedQuery", "RecallMetrics",
                        "FeedbackAssigner", "FeedbackQuerySplit", "FeedbackEffectMetrics")) {
                    assertFalse(bytes.contains(forbidden), type + " references " + forbidden);
                }
            }
        }
    }

    /** Copies the imported arms, so a test that records events never changes another test's roots. */
    private static Path copyArms(String name) throws IOException {
        var target = directory.resolve("copy-" + name);
        try (var paths = Files.walk(arms)) {
            for (var path : paths.toList()) {
                Files.copy(path, target.resolve(arms.relativize(path).toString()));
            }
        }
        return target;
    }
}
