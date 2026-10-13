package monada.neuron.resonance.adapter.experience;

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
            public Optional<HistoryEntry> loadEvent(ScopeId scope, EventId eventId, int revision) {
                return memory.loadEvent(scope, eventId, revision);
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
    void correctedRoutedStageCarriesRevisionAndTheEarliestSupersededSequence() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = fixtures.exec("x-fix").attempt(A1, 1, Optional.empty());
            var stage = exec.routedStage(A1, envelope("x-fix", "a1", "implement"));
            long originalSequence = memory.history(SCOPE).highWatermark();
            exec.correctRoutedStage(stage, 2, A1, envelope("x-fix", "a1", "implement"));
            exec.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            exec.finish(A1, AttemptResult.COMPLETED);
            exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            var imported = new StoreExperienceTranslator(memory, fixtures.resolver()).importSamples(
                    ExperienceExportRequest.of(SCOPE.value(), memory.history(SCOPE).highWatermark(), 10, 10));
            assertEquals(1, imported.observations().size(), imported.rejections().toString());
            RoutingObservation observation = imported.observations().getFirst();
            assertEquals(1, observation.revision());
            assertEquals(Feature.known(originalSequence), observation.supersedesSequence());
            assertTrue(observation.ledgerSequence() > originalSequence);
        }
    }
}
