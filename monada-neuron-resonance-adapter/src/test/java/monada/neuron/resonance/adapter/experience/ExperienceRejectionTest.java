package monada.neuron.resonance.adapter.experience;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceSamplePage;
import com.monada.api.execution.ProjectionStatus;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.HypotheticalCost;
import monada.neuron.routing.outcome.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static monada.neuron.resonance.adapter.experience.ExperienceFixtures.A1;
import static monada.neuron.resonance.adapter.experience.ExperienceFixtures.SCOPE;
import static monada.neuron.resonance.adapter.experience.ExperienceFixtures.envelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExperienceRejectionTest {

    @TempDir
    Path root;

    /** Imports one execution recorded by {@code recorder} and returns the single rejection reason. */
    private RejectionReason rejectionOf(String execution, java.util.function.BiConsumer<ExperienceFixtures,
            ExperienceFixtures.Exec> recorder) {
        try (ExecutionMemory memory = ExecutionMemory.open(freshStore(), SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            var exec = fixtures.exec(execution).attempt(A1, 1, Optional.empty());
            recorder.accept(fixtures, exec);
            exec.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            exec.finish(A1, AttemptResult.COMPLETED);
            exec.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            ExperienceImport imported = new StoreExperienceTranslator(memory, fixtures.resolver())
                    .importSamples(ExperienceExportRequest.of(SCOPE.value(), memory.history(SCOPE).highWatermark(), 10, 10));
            assertTrue(imported.observations().isEmpty(), "must not be admitted");
            assertEquals(1, imported.rejections().size());
            return imported.rejections().getFirst().reason();
        }
    }

    private Path freshStore() {
        try {
            return java.nio.file.Files.createTempDirectory(root, "store");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static RoutingEnvelope with(RoutingEnvelope e, Function<RoutingEnvelope, RoutingEnvelope> change) {
        return change.apply(e);
    }

    @Test
    void stageWithoutAnEnvelopeReferenceIsMissing() {
        assertEquals(RejectionReason.MISSING_ENVELOPE_REFERENCE,
                rejectionOf("x", (f, e) -> e.plainStage(A1, "implement")));
    }

    @Test
    void unresolvedEnvelope() {
        assertEquals(RejectionReason.ENVELOPE_UNRESOLVED, rejectionOf("x",
                (f, e) -> e.stageWithReference(A1, "implement", "ghost", Optional.of("ab".repeat(32)))));
    }

    @Test
    void wrongOrMissingDigest() {
        assertEquals(RejectionReason.ENVELOPE_DIGEST_MISMATCH, rejectionOf("x", (f, e) -> {
            f.publish("env", envelope("x", "a1", "implement"));
            e.stageWithReference(A1, "implement", "env", Optional.of("00".repeat(32)));
        }));
        assertEquals(RejectionReason.ENVELOPE_DIGEST_MISMATCH, rejectionOf("y", (f, e) -> {
            f.publish("env", envelope("y", "a1", "implement"));
            e.stageWithReference(A1, "implement", "env", Optional.empty());
        }));
    }

    @Test
    void envelopeOfAnotherExecutionAttemptOrStageIsNeverAdmittedEvenWithAValidDigest() {
        for (RoutingEnvelope foreign : List.of(
                envelope("someone-else", "a1", "implement"),
                envelope("x", "a9", "implement"),
                envelope("x", "a1", "other-stage"))) {
            assertEquals(RejectionReason.ENVELOPE_IDENTITY_MISMATCH, rejectionOf("x", (f, e) -> {
                f.publish("env", foreign);
                e.stageWithReference(A1, "implement", "env", Optional.of(foreign.digest()));
            }), foreign.decisionRef().toString());
        }
    }

    @Test
    void descriptorThatDiffersFromTheRecordedRouteIsRejectedEvenWithAValidDigest() {
        RoutingEnvelope base = envelope("x", "a1", "implement");
        for (ExecutedRouteDescriptor descriptor : List.of(
                new ExecutedRouteDescriptor("worker-b", Feature.known("prov"), Feature.known("small"),
                        Feature.known("low"), base.descriptor().billingMode()),
                new ExecutedRouteDescriptor("worker-a", Feature.known("prov"), Feature.known("large"),
                        Feature.known("low"), base.descriptor().billingMode()),
                new ExecutedRouteDescriptor("worker-a", Feature.known("prov"), Feature.unknown(),
                        Feature.known("low"), base.descriptor().billingMode()),
                new ExecutedRouteDescriptor("worker-a", Feature.known("prov"), Feature.known("small"),
                        Feature.known("low"), monada.neuron.routing.catalog.BillingMode.LOCAL))) {
            RoutingEnvelope other = with(base, e -> new RoutingEnvelope(e.contractVersion(), e.decisionRef(), e.route(),
                    descriptor, e.catalogVersion(), e.routingPolicyId(), e.routingPolicyVersion(),
                    e.evaluationPolicyId(), e.evaluationPolicyVersion(), e.cohortBinding(), e.executionMode()));
            assertEquals(RejectionReason.ENVELOPE_ROUTE_MISMATCH, rejectionOf("x", (f, e) -> {
                f.publish("env", other);
                e.stageWithReference(A1, "implement", "env", Optional.of(other.digest()));
            }), descriptor.toString());
        }
    }

    @Test
    void envelopeWithoutCohortBinding() {
        RoutingEnvelope base = envelope("x", "a1", "implement");
        RoutingEnvelope bare = new RoutingEnvelope(base.contractVersion(), base.decisionRef(), base.route(),
                base.descriptor(), base.catalogVersion(), base.routingPolicyId(), base.routingPolicyVersion(),
                base.evaluationPolicyId(), base.evaluationPolicyVersion(), Feature.unknown(), base.executionMode());
        assertEquals(RejectionReason.MISSING_COHORT_BINDING, rejectionOf("x", (f, e) -> {
            f.publish("env", bare);
            e.stageWithReference(A1, "implement", "env", Optional.of(bare.digest()));
        }));
    }

    @Test
    void twoRoutedStagesAreAmbiguous() {
        assertEquals(RejectionReason.AMBIGUOUS_ROUTED_STAGE, rejectionOf("x", (f, e) -> {
            e.routedStage(A1, envelope("x", "a1", "implement"));
            e.routedStage(A1, envelope("x", "a1", "implement"));
        }));
    }

    @Test
    void moreEvidenceThanTheNeutralRecordBoundIsMalformedNotTruncated() {
        assertEquals(RejectionReason.MALFORMED_SAMPLE, rejectionOf("x", (f, e) -> {
            e.routedStage(A1, envelope("x", "a1", "implement"));
            for (int i = 0; i < 33; i++) {
                e.evidence(A1, QualityDimension.values()[i % 6], ObservationState.PASS);
            }
        }));
    }

    @Test
    void costAnnotationsAreValidatedBeforeAdmission() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            fixtures.accepted("x");
            List<HypotheticalCost> duplicateCurrency = List.of(cost("USD"), cost("USD"));
            ExperienceImport imported = new StoreExperienceTranslator(memory, fixtures.resolver(),
                    (s, t, x) -> duplicateCurrency).importSamples(
                            ExperienceExportRequest.of(SCOPE.value(), memory.history(SCOPE).highWatermark(), 10, 10));
            assertEquals(RejectionReason.INVALID_ANNOTATION, imported.rejections().getFirst().reason());
        }
    }

    private static HypotheticalCost cost(String currency) {
        return new HypotheticalCost(new Money(new BigDecimal("1"), currency), "prices", "1", "api list price");
    }

    // --- crafted pages: schema, scope, projection, duplicates and the sample cap -------------------------------------

    private ExperienceSamplePage realPage(ExperienceFixtures fixtures, ExecutionMemory memory) {
        fixtures.accepted("x-1");
        fixtures.accepted("x-2");
        long cutoff = memory.history(SCOPE).highWatermark();
        return memory.exportSamples(com.monada.api.execution.ExportRequest.first(SCOPE,
                com.monada.api.execution.ExportCutoff.atSequence(cutoff), 10, com.monada.api.execution.ExportView.ADAPTER_READY,
                com.monada.api.execution.ExportEvidencePolicy.executionPolicyOnly()));
    }

    private static StoreExperienceTranslator.Reads serving(ExecutionMemory memory, ExperienceSamplePage page) {
        return new StoreExperienceTranslator.Reads() {
            @Override
            public com.monada.core.execution.ScopeId scope() {
                return memory.scope();
            }

            @Override
            public ExperienceSamplePage export(com.monada.api.execution.ExportRequest request) {
                return page;
            }

            @Override
            public Optional<com.monada.api.execution.HistoryEntry> loadEvent(com.monada.core.execution.ScopeId scope,
                    com.monada.core.execution.EventId eventId, int revision) {
                return memory.loadEvent(scope, eventId, revision);
            }

            @Override
            public long highWatermark(com.monada.core.execution.ScopeId scope) {
                return memory.history(scope).highWatermark();
            }
        };
    }

    private static ExperienceSamplePage rebuilt(ExperienceSamplePage p, String schema, com.monada.core.execution.ScopeId scope,
            ExperienceSamplePage.ProjectionStamp projection, List<com.monada.api.execution.ExperienceSample> samples) {
        return new ExperienceSamplePage(schema, scope, p.view(), p.additionalGates(), p.ledger(), projection, samples,
                false, Optional.empty());
    }

    @Test
    void unsupportedSchemaIsReportedAndNothingIsGuessed() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            ExperienceSamplePage page = realPage(fixtures, memory);
            var crafted = rebuilt(page, "execution-sample/2", SCOPE, page.projection(), page.samples());
            ExperienceImport imported = new StoreExperienceTranslator(serving(memory, crafted), fixtures.resolver(),
                    HypotheticalCostAnnotations.none())
                    .importSamples(ExperienceExportRequest.of(SCOPE.value(), 1, 10, 10));
            assertTrue(imported.observations().isEmpty());
            assertEquals(List.of(ImportDiagnostic.UNSUPPORTED_SCHEMA), imported.diagnostics());
        }
    }

    @Test
    void aPageOfAnotherScopeIsRefusedBeforeAnySampleIsAdmitted() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            ExperienceSamplePage page = realPage(fixtures, memory);
            var crafted = rebuilt(page, page.schema(), com.monada.core.execution.ScopeId.of("scope-other"),
                    page.projection(), page.samples());
            ExperienceImport imported = new StoreExperienceTranslator(serving(memory, crafted), fixtures.resolver(),
                    HypotheticalCostAnnotations.none())
                    .importSamples(ExperienceExportRequest.of(SCOPE.value(), 1, 10, 10));
            assertTrue(imported.observations().isEmpty());
            assertEquals(List.of(ImportDiagnostic.SCOPE_MISMATCH), imported.diagnostics());
        }
    }

    @Test
    void aStaleProjectionIsReportedButDoesNotBlockTheExport() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            ExperienceSamplePage page = realPage(fixtures, memory);
            var stale = new ExperienceSamplePage.ProjectionStamp(page.projection().formatVersion(),
                    page.projection().textScheme(), ProjectionStatus.State.STALE, false);
            var crafted = rebuilt(page, page.schema(), SCOPE, stale, page.samples());
            ExperienceImport imported = new StoreExperienceTranslator(serving(memory, crafted), fixtures.resolver(),
                    HypotheticalCostAnnotations.none())
                    .importSamples(ExperienceExportRequest.of(SCOPE.value(), 1, 10, 10));
            assertEquals(2, imported.observations().size());
            assertEquals(List.of(ImportDiagnostic.PROJECTION_NOT_CURRENT), imported.diagnostics());
        }
    }

    @Test
    void anExecutionSeenTwiceIsRefusedNotCountedTwice() {
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            ExperienceSamplePage page = realPage(fixtures, memory);
            var doubled = new ArrayList<>(page.samples());
            doubled.add(page.samples().getFirst());
            var crafted = rebuilt(page, page.schema(), SCOPE, page.projection(), doubled);
            ExperienceImport imported = new StoreExperienceTranslator(serving(memory, crafted), fixtures.resolver(),
                    HypotheticalCostAnnotations.none())
                    .importSamples(ExperienceExportRequest.of(SCOPE.value(), 1, 10, 10));
            assertEquals(2, imported.observations().size());
            assertEquals(List.of(RejectionReason.DUPLICATE_OR_CONFLICTING_VERSION),
                    imported.rejections().stream().map(ExperienceRejection::reason).toList());
        }
    }
}
