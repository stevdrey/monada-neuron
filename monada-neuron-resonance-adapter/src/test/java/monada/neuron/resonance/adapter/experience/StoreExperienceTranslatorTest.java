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
}
