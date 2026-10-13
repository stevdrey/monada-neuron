package monada.neuron.resonance.adapter.experience;

import java.io.IOException;
import java.io.UncheckedIOException;
import com.monada.core.execution.ExecutionId;
import com.monada.api.execution.ExecutionView;
import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceSamplePage;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.HistoryEntry;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static monada.neuron.resonance.adapter.experience.ExperienceFixtures.A1;
import static monada.neuron.resonance.adapter.experience.ExperienceFixtures.SCOPE;
import static monada.neuron.resonance.adapter.experience.ExperienceFixtures.envelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExperiencePagingTest {

    @TempDir
    Path root;

    /** Delegates to a real memory and runs a hook after every export call, to append between pages. */
    private static StoreExperienceTranslator.Reads hooked(ExecutionMemory memory, IntConsumer afterExport,
                                                          AtomicInteger calls) {
        return new StoreExperienceTranslator.Reads() {
            @Override
            public ScopeId scope() {
                return memory.scope();
            }

            @Override
            public ExperienceSamplePage export(ExportRequest request) {
                ExperienceSamplePage page = memory.exportSamples(request);
                afterExport.accept(calls.incrementAndGet());
                return page;
            }

            @Override
            public List<HistoryEntry> executionHistory(ScopeId scope, ExecutionId execution,
                                                       long cutoffSequence) {
                return memory.loadExecution(scope, execution, cutoffSequence).map(ExecutionView::history)
                        .orElse(List.of());
            }

            @Override
            public long highWatermark(ScopeId scope) {
                return memory.history(scope).highWatermark();
            }
        };
    }

    @Test
    void appendBetweenPagesKeepsTheSameSnapshotAndRepeatsAreEqual() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            fixtures.accepted("x-1");
            fixtures.accepted("x-2");
            fixtures.accepted("x-3");
            long cutoff = memory.history(SCOPE).highWatermark();
            var request = ExperienceExportRequest.of(SCOPE.value(), cutoff, 1, 100);

            var plain = new StoreExperienceTranslator(memory, fixtures.resolver()).importSamples(request);

            var calls = new AtomicInteger();
            var translator = new StoreExperienceTranslator(
                    hooked(memory, call -> {
                        if (call == 1) {
                            fixtures.accepted("x-appended-while-paging");
                        }
                    }, calls),
                    fixtures.resolver(), HypotheticalCostAnnotations.none());
            var paged = translator.importSamples(request);

            assertEquals(3, paged.snapshot().orElseThrow().pages());
            assertEquals(3, paged.observations().size());
            assertEquals(plain, paged, "an append between pages must not enter the pinned snapshot");
            assertTrue(memory.history(SCOPE).highWatermark() > cutoff, "the append really happened");
            assertEquals(plain.snapshot().orElseThrow().ledgerPrefixDigest(),
                    paged.snapshot().orElseThrow().ledgerPrefixDigest());

            // a new snapshot at the new watermark sees the appended execution
            var later = new StoreExperienceTranslator(memory, fixtures.resolver()).importSamples(
                    ExperienceExportRequest.of(SCOPE.value(), memory.history(SCOPE).highWatermark(), 1, 100));
            assertEquals(4, later.observations().size());
        }
    }

    @Test
    void sampleCapStopsPagingAndSaysSo() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            for (int i = 0; i < 5; i++) {
                fixtures.accepted("x-" + i);
            }
            long cutoff = memory.history(SCOPE).highWatermark();
            var translator = new StoreExperienceTranslator(memory, fixtures.resolver());

            var capped = translator.importSamples(ExperienceExportRequest.of(SCOPE.value(), cutoff, 2, 3));
            assertEquals(3, capped.observations().size());
            assertEquals(3, capped.snapshot().orElseThrow().samplesExamined());
            assertEquals(List.of(ImportDiagnostic.SAMPLE_CAP_REACHED), capped.diagnostics());

            var exact = translator.importSamples(ExperienceExportRequest.of(SCOPE.value(), cutoff, 2, 5));
            assertEquals(5, exact.observations().size());
            assertTrue(exact.diagnostics().isEmpty(), "reaching the cap exactly at the end is not truncation");
        }
    }

    @Test
    void observationsFollowTheStoreTotalOrder() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            fixtures.accepted("x-b");
            fixtures.accepted("x-a");
            var imported = new StoreExperienceTranslator(memory, fixtures.resolver()).importSamples(
                    ExperienceExportRequest.of(SCOPE.value(), memory.history(SCOPE).highWatermark(), 1, 10));
            List<Long> sequences = imported.observations().stream().map(RoutingObservation::ledgerSequence).toList();
            assertEquals(sequences.stream().sorted().toList(), sequences);
            assertEquals("x-b", imported.observations().getFirst().outcome().decisionRef().executionId());
        }
    }

    @Test
    void correctedRoutedStageKeepsItsIdentityAndAdvancesTheVersionWithTheOutcomeAsAncestry() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = fixtures.exec("x-fix").attempt(A1, 1, Optional.empty());
            var stage = exec.routedStage(A1, envelope("x-fix", "a1", "implement"));
            exec.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            exec.finish(A1, AttemptResult.COMPLETED);
            exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            long applied = memory.history(SCOPE).highWatermark();
            var translator = new StoreExperienceTranslator(memory, fixtures.resolver());
            RoutingObservation before = translator.importSamples(
                    ExperienceExportRequest.of(SCOPE.value(), applied, 10, 10)).observations().getFirst();

            exec.correctRoutedStage(stage, 2, A1, envelope("x-fix", "a1", "implement"));
            RoutingObservation after = translator.importSamples(ExperienceExportRequest.of(SCOPE.value(),
                    memory.history(SCOPE).highWatermark(), 10, 10)).observations().getFirst();

            assertEquals(0, before.revision());
            assertEquals(before.experienceRef(), after.experienceRef(), "the identity survives a stage correction");
            assertEquals(1, after.revision());
            assertEquals(Feature.known(applied), after.supersedesSequence(),
                    "the earliest version could have been applied at the last event it needed");
            assertTrue(after.ledgerSequence() > applied);
        }
    }

    @Test
    void aCorrectionBeforeAnyObservationCouldExistDoesNotAdvanceTheVersion() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = fixtures.exec("x-early").attempt(A1, 1, Optional.empty());
            var stage = exec.routedStage(A1, envelope("x-early", "a1", "implement"));
            exec.correctRoutedStage(stage, 2, A1, envelope("x-early", "a1", "implement"));
            exec.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            exec.finish(A1, AttemptResult.COMPLETED);
            exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            RoutingObservation observation = new StoreExperienceTranslator(memory, fixtures.resolver()).importSamples(
                    ExperienceExportRequest.of(SCOPE.value(), memory.history(SCOPE).highWatermark(), 10, 10))
                    .observations().getFirst();
            assertEquals(0, observation.revision());
            assertEquals(Feature.unknown(), observation.supersedesSequence());
        }
    }

    /** Routed attempt A failed, repair attempt B is open and accepted: new events on B change the effective sample. */
    private ExperienceFixtures.Exec openRepair(ExperienceFixtures fixtures, String execution) {
        var exec = fixtures.exec(execution).attempt(A1, 1, Optional.empty());
        exec.routedStage(A1, envelope(execution, "a1", "implement"));
        exec.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL);
        exec.finish(A1, AttemptResult.FAILED);
        exec.attempt(ExperienceFixtures.A2, 2, Optional.of(A1));
        exec.evidence(ExperienceFixtures.A2, QualityDimension.TESTS, ObservationState.PASS);
        exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, ExperienceFixtures.A2));
        return exec;
    }

    @Test
    void newEvidenceOnAnOpenAcceptedAttemptVersionsTheObservationWithoutAnyCorrection() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = openRepair(fixtures, "x-open");
            long snapshotA = memory.history(SCOPE).highWatermark();
            var translator = new StoreExperienceTranslator(memory, fixtures.resolver());
            RoutingObservation a = translator.importSamples(
                    ExperienceExportRequest.of(SCOPE.value(), snapshotA, 10, 10)).observations().getFirst();

            exec.evidence(ExperienceFixtures.A2, QualityDimension.TESTS, ObservationState.FAIL);
            long snapshotB = memory.history(SCOPE).highWatermark();
            RoutingObservation b = translator.importSamples(
                    ExperienceExportRequest.of(SCOPE.value(), snapshotB, 10, 10)).observations().getFirst();

            assertEquals(0, a.revision());
            assertEquals(Feature.unknown(), a.supersedesSequence());
            assertEquals(a.experienceRef(), b.experienceRef(), "stable identity");
            assertEquals(1, b.revision());
            assertEquals(Feature.known(a.ledgerSequence()), b.supersedesSequence());
            assertTrue(b.ledgerSequence() > a.ledgerSequence());
            var effective = b.outcome().observations().stream()
                    .filter(o -> o.attemptId().equals("a2")).map(o -> o.state()).toList();
            assertTrue(effective.contains(QualityState.FAIL),
                    "the newly appended FAIL is part of the effective evidence");

            // repeated imports of one snapshot are equal, and an older snapshot is still version 0
            assertEquals(b, translator.importSamples(ExperienceExportRequest.of(SCOPE.value(), snapshotB, 10, 10))
                    .observations().getFirst());
            assertEquals(a, translator.importSamples(ExperienceExportRequest.of(SCOPE.value(), snapshotA, 10, 10))
                    .observations().getFirst());
        }
    }

    @Test
    void aNewCurrentOutcomeVersionsTheObservationToo() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = openRepair(fixtures, "x-newout");
            var translator = new StoreExperienceTranslator(memory, fixtures.resolver());
            long snapshotA = memory.history(SCOPE).highWatermark();
            RoutingObservation a = translator.importSamples(
                    ExperienceExportRequest.of(SCOPE.value(), snapshotA, 10, 10)).observations().getFirst();

            exec.outcome(Outcome.rejected(OutcomeOrigin.VALIDATED));
            RoutingObservation b = translator.importSamples(ExperienceExportRequest.of(SCOPE.value(),
                    memory.history(SCOPE).highWatermark(), 10, 10)).observations().getFirst();

            assertEquals(a.experienceRef(), b.experienceRef());
            assertEquals(OutcomeStatus.ACCEPTED, a.outcome().outcomeStatus());
            assertEquals(OutcomeStatus.REJECTED, b.outcome().outcomeStatus());
            assertEquals(1, b.revision());
            assertEquals(Feature.known(a.ledgerSequence()), b.supersedesSequence());
        }
    }
}
