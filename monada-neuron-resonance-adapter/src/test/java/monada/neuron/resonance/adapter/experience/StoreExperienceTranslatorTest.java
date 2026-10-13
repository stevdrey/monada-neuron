package monada.neuron.resonance.adapter.experience;

import com.monada.api.execution.ExecutionMemory;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import monada.neuron.resonance.adapter.ResonanceStoreAdapterException;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.InvocationStatus;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.ResourceSummary;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.StageAttribution;
import monada.neuron.routing.catalog.ResourceValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static monada.neuron.resonance.adapter.experience.ExperienceFixtures.A1;
import static monada.neuron.resonance.adapter.experience.ExperienceFixtures.SCOPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StoreExperienceTranslatorTest {

    @TempDir
    Path root;

    private static ExperienceExportRequest request(long cutoff) {
        return ExperienceExportRequest.of(SCOPE.value(), cutoff, 10, 100);
    }

    @Test
    void appendReopenExportTranslatePreservesScopeRevisionCheckpointAndEvidence() {
        RoutingEnvelopeResolver resolver;
        long watermark;
        ExperienceFixtures fixtures;
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            fixtures = new ExperienceFixtures(memory);
            fixtures.accepted("x-ok");
            fixtures.rejected("x-rej");
            resolver = fixtures.resolver();
            watermark = memory.history(SCOPE).highWatermark();
        }
        try (ExecutionMemory memory = ExecutionMemory.openReadOnly(root, SCOPE)) {
            var translator = new StoreExperienceTranslator(memory, resolver);
            assertEquals(watermark, translator.highWatermark());
            ExperienceImport imported = translator.importSamples(request(watermark));

            assertTrue(imported.rejections().isEmpty(), imported.rejections().toString());
            assertTrue(imported.diagnostics().isEmpty(), imported.diagnostics().toString());
            assertEquals(2, imported.observations().size());
            ExperienceImport.Snapshot snapshot = imported.snapshot().orElseThrow();
            assertEquals("execution-sample/1", snapshot.schema());
            assertEquals(watermark, snapshot.cutoffSequence());
            assertEquals(64, snapshot.ledgerPrefixDigest().length());

            RoutingObservation ok = imported.observations().stream()
                    .filter(o -> o.outcome().decisionRef().executionId().equals("x-ok")).findFirst().orElseThrow();
            assertEquals(OutcomeStatus.ACCEPTED, ok.outcome().outcomeStatus());
            assertEquals(InvocationStatus.COMPLETED, ok.outcome().invocation());
            assertEquals(watermark, ok.outcome().cutoff());
            assertEquals(0, ok.revision());
            assertEquals(Feature.unknown(), ok.supersedesSequence());
            assertEquals("route-a", ok.outcome().executedRoute().routeId());
            assertEquals(ExperienceFixtures.COHORT, ok.outcome().cohortBinding());
            assertEquals("rev-1", ok.outcome().sourceFingerprint());
            assertEquals(1, ok.outcome().observations().size());
            assertEquals(1, ok.outcome().judgedRevisions().size());
            assertEquals(StageAttribution.Basis.STAGE_LOCAL, ok.outcome().attribution().basis());
            assertEquals(Feature.known("a1"), ok.outcome().acceptedAttemptId());
            assertTrue(ok.experienceRef().startsWith("xr1|"));
            assertTrue(ok.ledgerSequence() >= ok.outcome().outcomeSequence());

            // the translated observation feeds the existing #63 evaluator without further adaptation
            assertEquals(OutcomeStatus.ACCEPTED, ok.outcome().outcomeStatus());
        }
    }

    @Test
    void usageUnknownAndNotMeasuredAreNeverZero() {
        ExperienceImport imported;
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            fixtures.accepted("x-ok");
            imported = new StoreExperienceTranslator(memory, fixtures.resolver())
                    .importSamples(request(memory.history(SCOPE).highWatermark()));
        }
        ResourceSummary resources = imported.observations().getFirst().outcome().resources();
        var counters = resources.counters();
        assertEquals(ResourceValue.Provenance.REPORTED, ((ResourceValue.Known) counters.stream()
                .filter(c -> c.kind().name().equals("INPUT_TOKENS")).findFirst().orElseThrow().value()).provenance());
        assertInstanceOf(ResourceValue.Unknown.class, counters.stream()
                .filter(c -> c.kind().name().equals("OUTPUT_TOKENS")).findFirst().orElseThrow().value());
        assertTrue(resources.actualBilling().isEmpty());
        assertTrue(resources.hypotheticalCosts().isEmpty());
    }

    @Test
    void hypotheticalCostComesOnlyFromTheHostSeam() {
        var cost = new monada.neuron.routing.outcome.HypotheticalCost(
                new monada.neuron.routing.outcome.Money(new java.math.BigDecimal("0.0123"), "USD"), "prices", "1",
                "api list price");
        ExperienceImport imported;
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            fixtures.accepted("x-ok");
            imported = new StoreExperienceTranslator(memory, fixtures.resolver(),
                    (scope, task, execution) -> execution.equals("x-ok") ? java.util.List.of(cost) : java.util.List.of())
                    .importSamples(request(memory.history(SCOPE).highWatermark()));
        }
        var resources = imported.observations().getFirst().outcome().resources();
        assertEquals(java.util.List.of(cost), resources.hypotheticalCosts());
        assertTrue(resources.actualBilling().isEmpty());
    }

    @Test
    void correctedEvidenceKeepsBothRevisionsAndTheLatestWins() {
        ExperienceImport imported;
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = fixtures.exec("x-fix").attempt(A1, 1, Optional.empty());
            exec.routedStage(A1, ExperienceFixtures.envelope("x-fix", "a1", "implement"));
            var evidence = exec.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL);
            exec.correctEvidence(evidence, 2, A1, QualityDimension.TESTS, ObservationState.PASS);
            exec.finish(A1, AttemptResult.COMPLETED);
            exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            imported = new StoreExperienceTranslator(memory, fixtures.resolver())
                    .importSamples(request(memory.history(SCOPE).highWatermark()));
        }
        assertEquals(1, imported.observations().size(), imported.rejections().toString());
        RoutingObservation observation = imported.observations().getFirst();
        var evidence = observation.outcome().observations();
        assertEquals(1, evidence.size());
        assertEquals(1, evidence.getFirst().revision());
        assertEquals(monada.neuron.routing.outcome.QualityState.PASS, evidence.getFirst().state());

        assertEquals(1, observation.outcome().judgedRevisions().getFirst().revision());
    }

    @Test
    void incompleteAndNegativeExecutionsAreExplicitNotSilentlyDropped() {
        ExperienceImport imported;
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var pending = fixtures.exec("x-pending").attempt(A1, 1, Optional.empty());
            pending.routedStage(A1, ExperienceFixtures.envelope("x-pending", "a1", "implement"));
            pending.outcome(Outcome.pending(OutcomeOrigin.VALIDATED));
            fixtures.rejected("x-rej");
            var noOutcome = fixtures.exec("x-no-outcome").attempt(A1, 1, Optional.empty());
            noOutcome.routedStage(A1, ExperienceFixtures.envelope("x-no-outcome", "a1", "implement"));
            noOutcome.finish(A1, AttemptResult.COMPLETED);
            imported = new StoreExperienceTranslator(memory, fixtures.resolver())
                    .importSamples(request(memory.history(SCOPE).highWatermark()));
        }
        assertEquals(1, imported.observations().size());
        assertEquals(OutcomeStatus.REJECTED, imported.observations().getFirst().outcome().outcomeStatus());
        assertEquals(2, imported.rejections().size());
        assertEquals(Map.of("x-pending", RejectionReason.ROUTED_ATTEMPT_UNFINISHED,
                "x-no-outcome", RejectionReason.MISSING_OUTCOME), reasonsByExecution(imported));
    }

    private static Map<String, RejectionReason> reasonsByExecution(ExperienceImport imported) {
        Map<String, RejectionReason> reasons = new java.util.TreeMap<>();
        for (ExperienceRejection rejection : imported.rejections()) {
            String ref = rejection.executionRef();
            reasons.put(ref.contains("x-pending") ? "x-pending" : "x-no-outcome", rejection.reason());
        }
        return reasons;
    }

    @Test
    void sameInputGivesEqualResults() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            fixtures.accepted("x-a");
            fixtures.rejected("x-b");
            fixtures.accepted("x-c");
            long cutoff = memory.history(SCOPE).highWatermark();
            var translator = new StoreExperienceTranslator(memory, fixtures.resolver());
            assertEquals(translator.importSamples(request(cutoff)), translator.importSamples(request(cutoff)));
        }
    }

    @Test
    void readingLeavesEveryStoreFileUnchangedAndDoesNotCloseTheMemory() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            fixtures.accepted("x-a");
            fixtures.rejected("x-b");
            long cutoff = memory.history(SCOPE).highWatermark();
            Map<String, String> before = ExperienceFixtures.digests(root);
            assertFalse(before.isEmpty());

            new StoreExperienceTranslator(memory, fixtures.resolver()).importSamples(request(cutoff));

            assertEquals(before, ExperienceFixtures.digests(root));
            assertEquals(cutoff, memory.history(SCOPE).highWatermark(), "the ledger did not move");
            assertEquals(SCOPE, memory.scope(), "the memory is still open and usable");
        }
    }

    @Test
    void aCutoffBeyondTheLedgerIsAStoreFailureNotAPartialImport() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            fixtures.accepted("x-a");
            var translator = new StoreExperienceTranslator(memory, fixtures.resolver());
            assertThrows(ResonanceStoreAdapterException.class, () -> translator.importSamples(request(10_000)));
        }
    }

    @Test
    void aRequestForAnotherScopeReadsNothing() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            fixtures.accepted("x-a");
            ExperienceImport imported = new StoreExperienceTranslator(memory, fixtures.resolver())
                    .importSamples(ExperienceExportRequest.of("scope-other", 1, 10, 10));
            assertTrue(imported.observations().isEmpty());
            assertEquals(java.util.List.of(ImportDiagnostic.SCOPE_MISMATCH), imported.diagnostics());
            assertTrue(imported.snapshot().isEmpty());
        }
    }

    @Test
    void correctingOnlyEvidenceStillVersionsTheObservationWithAKnownAncestor() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = fixtures.exec("x-ev").attempt(A1, 1, Optional.empty());
            exec.routedStage(A1, ExperienceFixtures.envelope("x-ev", "a1", "implement"));
            var evidence = exec.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            exec.finish(A1, AttemptResult.COMPLETED);
            exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            var translator = new StoreExperienceTranslator(memory, fixtures.resolver());

            long original = memory.history(SCOPE).highWatermark();
            RoutingObservation before = translator.importSamples(request(original)).observations().getFirst();
            assertEquals(0, before.revision());

            var secondRevision = exec.correctEvidence(evidence, 2, A1, QualityDimension.TESTS, ObservationState.FAIL);
            long corrected = memory.history(SCOPE).highWatermark();
            RoutingObservation after = translator.importSamples(request(corrected)).observations().getFirst();

            assertEquals(before.experienceRef(), after.experienceRef(), "same observation identity");
            assertEquals(1, after.revision());
            assertTrue(after.ledgerSequence() > before.ledgerSequence());
            assertTrue(after.supersedesSequence() instanceof Feature.Known<Long>);
            assertTrue(((Feature.Known<Long>) after.supersedesSequence()).value() < after.ledgerSequence());

            exec.correctEvidence(secondRevision, 3, A1, QualityDimension.TESTS, ObservationState.PASS);
            RoutingObservation twice = translator.importSamples(request(memory.history(SCOPE).highWatermark()))
                    .observations().getFirst();
            assertEquals(2, twice.revision(), "every correction advances the version");
        }
    }

    @Test
    void theStoreKeepsExecutionIdsUniquePerScopeSoTheDuplicateKeyNeverHidesADistinctExecution() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            fixtures.execOfTask("task-a", "run-1");
            // the ledger itself refuses a second start of run-1 under another task (the fixture asserts APPENDED)
            assertThrows(AssertionError.class, () -> fixtures.execOfTask("task-b", "run-1"));
        }
    }

    @Test
    void repairedChainDoesNotAttributeTheAcceptedAttemptsEvidenceToTheRoutedAttempt() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = fixtures.exec("x-rep").attempt(A1, 1, Optional.empty());
            exec.routedStage(A1, ExperienceFixtures.envelope("x-rep", "a1", "implement"));
            exec.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL);
            exec.finish(A1, AttemptResult.FAILED);
            exec.attempt(ExperienceFixtures.A2, 2, Optional.of(A1));
            exec.plainStage(ExperienceFixtures.A2, "repair");
            exec.evidence(ExperienceFixtures.A2, QualityDimension.TESTS, ObservationState.PASS);
            exec.finish(ExperienceFixtures.A2, AttemptResult.COMPLETED);
            exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, ExperienceFixtures.A2));

            var imported = new StoreExperienceTranslator(memory, fixtures.resolver())
                    .importSamples(request(memory.history(SCOPE).highWatermark()));
            assertEquals(1, imported.observations().size(), imported.rejections().toString());
            var attribution = imported.observations().getFirst().outcome().attribution();
            assertEquals(StageAttribution.Basis.CHAIN_ONLY, attribution.basis());
            assertEquals(2, attribution.chainStageCount());
        }
    }

    @Test
    void aRoutedRepairAttemptKeepsTheFullChainSizeInItsStageLocalAttribution() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = fixtures.exec("x-late").attempt(A1, 1, Optional.empty());
            exec.plainStage(A1, "first-try");
            exec.finish(A1, AttemptResult.FAILED);
            exec.attempt(ExperienceFixtures.A2, 2, Optional.of(A1));
            exec.routedStage(ExperienceFixtures.A2, ExperienceFixtures.envelope("x-late", "a2", "repair"));
            exec.evidence(ExperienceFixtures.A2, QualityDimension.TESTS, ObservationState.PASS);
            exec.finish(ExperienceFixtures.A2, AttemptResult.COMPLETED);
            exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, ExperienceFixtures.A2));

            var imported = new StoreExperienceTranslator(memory, fixtures.resolver())
                    .importSamples(request(memory.history(SCOPE).highWatermark()));
            assertEquals(1, imported.observations().size(), imported.rejections().toString());
            var attribution = imported.observations().getFirst().outcome().attribution();
            assertEquals(StageAttribution.Basis.STAGE_LOCAL, attribution.basis());
            assertEquals(2, attribution.chainStageCount());
        }
    }

    @Test
    void aFailingSupersededRevisionReadIsAnAdapterFailureNotAStoreException() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = fixtures.exec("x-fix").attempt(A1, 1, Optional.empty());
            var stage = exec.routedStage(A1, ExperienceFixtures.envelope("x-fix", "a1", "implement"));
            exec.correctRoutedStage(stage, 2, A1, ExperienceFixtures.envelope("x-fix", "a1", "implement"));
            exec.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            exec.finish(A1, AttemptResult.COMPLETED);
            exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            long cutoff = memory.history(SCOPE).highWatermark();

            var failing = new StoreExperienceTranslator.Reads() {
                @Override
                public com.monada.core.execution.ScopeId scope() {
                    return memory.scope();
                }

                @Override
                public com.monada.api.execution.ExperienceSamplePage export(
                        com.monada.api.execution.ExportRequest request) {
                    return memory.exportSamples(request);
                }

                @Override
                public Optional<com.monada.api.execution.HistoryEntry> loadEvent(
                        com.monada.core.execution.ScopeId scope, com.monada.core.execution.EventId eventId,
                        int revision) {
                    throw new java.io.UncheckedIOException(new java.io.IOException("disk gone"));
                }

                @Override
                public long highWatermark(com.monada.core.execution.ScopeId scope) {
                    return cutoff;
                }
            };
            var translator = new StoreExperienceTranslator(failing, fixtures.resolver(),
                    HypotheticalCostAnnotations.none());
            assertThrows(ResonanceStoreAdapterException.class, () -> translator.importSamples(request(cutoff)));
        }
    }

    @Test
    void anOversizedCompositeReferenceIsBoundedExactWhenItFitsAndHashedWhenItDoesNot() {
        String longToken = "t".repeat(128);
        var big = new com.monada.core.execution.ExperienceRef(com.monada.core.execution.ScopeId.of(longToken),
                com.monada.core.execution.TaskId.of(longToken), com.monada.core.execution.ExecutionId.of(longToken),
                Optional.of(com.monada.core.execution.AttemptId.of(longToken)),
                com.monada.core.execution.EventId.of(longToken), 1);
        String formatted = ExperienceRefs.format(big);
        assertTrue(formatted.startsWith("xr1h|"));
        assertEquals(formatted, ExperienceRefs.format(big), "deterministic");
        assertTrue(formatted.length() <= 512);
        var other = new com.monada.core.execution.ExperienceRef(big.scope(), big.task(), big.execution(),
                big.attempt(), com.monada.core.execution.EventId.of("u".repeat(128)), 1);
        assertFalse(formatted.equals(ExperienceRefs.format(other)), "different events stay distinguishable");
        new RoutingObservationRefProbe().accept(formatted);

        String small = ExperienceRefs.format(new com.monada.core.execution.ExperienceRef(
                com.monada.core.execution.ScopeId.of("s"), com.monada.core.execution.TaskId.of("t"),
                com.monada.core.execution.ExecutionId.of("e"), Optional.empty(),
                com.monada.core.execution.EventId.of("v"), 7));
        assertEquals("xr1|1:s|1:t|1:e|-|1:v", small, "the exact text is kept when it fits, without the revision");
    }

    /** The bounded reference must be accepted by the neutral record's text rule. */
    private static final class RoutingObservationRefProbe implements java.util.function.Consumer<String> {
        @Override
        public void accept(String ref) {
            monada.neuron.routing.token.OpaqueTokens.requireText(ref, "experienceRef");
        }
    }
}
