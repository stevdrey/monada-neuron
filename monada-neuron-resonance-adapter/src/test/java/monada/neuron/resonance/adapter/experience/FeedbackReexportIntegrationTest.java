package monada.neuron.resonance.adapter.experience;

import com.monada.api.execution.ExecutionMemory;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.BillingMode;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.RebuildOrder;
import monada.neuron.routing.feedback.Reconciliation;
import monada.neuron.routing.feedback.RevisionReconciler;
import monada.neuron.routing.feedback.RoutingFeedbackConfig;
import monada.neuron.routing.feedback.RoutingFeedbackPolicy;
import monada.neuron.routing.feedback.TargetBinding;
import monada.neuron.routing.feedback.TargetBindings;
import monada.neuron.routing.outcome.EvidencePolicy;
import monada.neuron.routing.outcome.RoutingObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static monada.neuron.resonance.adapter.experience.ExperienceFixtures.A1;
import static monada.neuron.resonance.adapter.experience.ExperienceFixtures.SCOPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sample that is exported again at a newer cutoff is the same claim: the export cutoff is the as-of context of the
 * read, not part of the sample's identity. Runs the real Store, the translator and the root feedback policy end to end.
 */
final class FeedbackReexportIntegrationTest {

    private static final UUID NODE = new UUID(0L, 7L);

    @TempDir
    Path root;

    private final RoutingFeedbackPolicy policy = new RoutingFeedbackPolicy(
            LexicographicRoutingPolicy.reference().definition(),
            new RoutingFeedbackConfig(0.8, -0.4, 0.0, Optional.empty()),
            new EvidencePolicy("gates", "1", Set.of(monada.neuron.routing.outcome.QualityDimension.TESTS)));
    private final RevisionReconciler reconciler = new RevisionReconciler();

    /** The request the fixture execution was routed for; its fingerprints are the ones the execution recorded. */
    private static RoutingRequest request(String execution) {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, SCOPE.value(), "task-" + execution, execution,
                A1.value(), "implement", "implement", 1L, "rev-1", "ctx-1", "constraints-1", "gates", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false, 100L);
    }

    private static RoutingDecision.Selected decide(RoutingRequest request) {
        var descriptor = RouteDescriptor.builder("route-a", 1).stages(List.of("implement"))
                .executionModes(List.of("sandboxed")).locality("hosted").build();
        var catalog = RouteCatalog.of("catalog-1", List.of(new CatalogEntry(descriptor, Availability.AVAILABLE, 1)));
        var routing = LexicographicRoutingPolicy.reference();
        var empty = RoutingPreference.empty(request, routing.definition(), routing.policyId(), routing.policyVersion());
        return (RoutingDecision.Selected) routing.decide(request, catalog, empty);
    }

    /** The envelope a host would publish for {@code decision}: every field is the decision's own. */
    private static RoutingEnvelope envelope(RoutingRequest request, RoutingDecision.Selected decision) {
        var provenance = decision.provenance();
        return new RoutingEnvelope(RoutingRequest.CONTRACT_VERSION, DecisionRef.of(request), decision.route(),
                new ExecutedRouteDescriptor("worker-a", Feature.known("prov"), Feature.known("small"),
                        Feature.known("low"), BillingMode.API_METERED),
                provenance.catalogVersion(), provenance.policyId(), provenance.policyVersion(), "gates", "1",
                Feature.known(decision.cohortBinding()), decision.executionMode());
    }

    private TargetBindings bindings(RoutingRequest request, RoutingDecision.Selected decision) {
        var definition = LexicographicRoutingPolicy.reference().definition();
        var key = new CohortKey(request.scopeId(), request.stageKind(), decision.cohortBinding().cohortBucket(),
                decision.route());
        return new TargetBindings(request.scopeId(), request.evaluationPolicyId(), request.evaluationPolicyVersion(),
                definition.mappingVersion(), definition.cohortMapping().featureSchemaVersion(),
                List.of(new TargetBinding(key, NODE)));
    }

    private static void recordExecution(ExperienceFixtures fixtures, String execution, RoutingEnvelope envelope,
            Outcome outcome) {
        var exec = fixtures.exec(execution).attempt(A1, 1, Optional.empty());
        exec.routedStage(A1, envelope);
        exec.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        exec.finish(A1, AttemptResult.COMPLETED);
        exec.outcome(outcome);
    }

    private RoutingObservation importOf(ExecutionMemory memory, ExperienceFixtures fixtures, long cutoff) {
        var imported = new StoreExperienceTranslator(memory, fixtures.resolver())
                .importSamples(ExperienceExportRequest.of(SCOPE.value(), cutoff, 10, 100));
        assertTrue(imported.rejections().isEmpty(), imported.rejections().toString());
        return imported.observations().stream()
                .filter(o -> o.outcome().decisionRef().executionId().equals("A")).findFirst().orElseThrow();
    }

    private Reconciliation deliver(List<AppliedRevision> ledger, RoutingObservation observation,
            RoutingRequest request, RoutingDecision.Selected decision) {
        FeedbackDerivation derivation = policy.derive(observation, decision, request, bindings(request, decision));
        return reconciler.reconcile(ledger, observation, derivation);
    }

    @Test
    void aSampleReExportedAtANewerCutoffDoesNotCreditTheNodeTwiceNorConflict() {
        var request = request("A");
        var decision = decide(request);
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            recordExecution(fixtures, "A", envelope(request, decision), Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            long c1 = memory.history(SCOPE).highWatermark();
            RoutingObservation first = importOf(memory, fixtures, c1);

            List<AppliedRevision> ledger = new ArrayList<>();
            int nodeEffects = 0;
            var firstDelivery = assertInstanceOf(Reconciliation.Apply.class, deliver(ledger, first, request, decision));
            assertTrue(firstDelivery.replaces().isEmpty());
            var derived = assertInstanceOf(FeedbackDerivation.Derived.class,
                    policy.derive(first, decision, request, bindings(request, decision)));
            assertEquals(FeedbackDisposition.REINFORCE, derived.feedback().disposition());
            assertEquals(NODE, derived.feedback().entries().getFirst().targetNodeId());
            ledger.add(AppliedRevision.of(derived.feedback()));
            nodeEffects++;

            // only another execution grows the Store, so the later export has a newer cutoff and nothing else changes
            recordExecution(fixtures, "B", envelope(request("B"), decide(request("B"))),
                    Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            long c2 = memory.history(SCOPE).highWatermark();
            assertTrue(c2 > c1);
            RoutingObservation again = importOf(memory, fixtures, c2);

            assertEquals(c1, first.outcome().cutoff());
            assertEquals(c2, again.outcome().cutoff());
            assertEquals(first.experienceRef(), again.experienceRef());
            assertEquals(first.revision(), again.revision());
            assertEquals(first.ledgerSequence(), again.ledgerSequence());
            assertNotEquals(first, again, "the observations differ only in the export cutoff");

            var second = deliver(ledger, again, request, decision);
            assertInstanceOf(Reconciliation.AlreadyApplied.class, second);
            assertEquals(1, nodeEffects, "the Node is credited once");
            assertEquals(1, ledger.size());

            // a rebuild fed with both exports collapses them to the later snapshot instead of rejecting a conflict
            List<RoutingObservation> order = new RebuildOrder().order(List.of(first, again));
            assertEquals(List.of(again), order);
            assertEquals(List.of(again), new RebuildOrder().order(List.of(again, first)));
        }
    }

    @Test
    void aNeutralSampleReExportedAtANewerCutoffIsAlreadyRecorded() {
        var request = request("A");
        var decision = decide(request);
        try (ExecutionMemory memory = ExecutionMemory.open(root, SCOPE)) {
            var fixtures = new ExperienceFixtures(memory);
            // an imported claim is never credited: the feedback is neutral and records the claim without a Node effect
            recordExecution(fixtures, "A", envelope(request, decision),
                    Outcome.accepted(OutcomeOrigin.IMPORTED_CLAIM, A1));
            RoutingObservation first = importOf(memory, fixtures, memory.history(SCOPE).highWatermark());

            List<AppliedRevision> ledger = new ArrayList<>();
            var derived = assertInstanceOf(FeedbackDerivation.Derived.class,
                    policy.derive(first, decision, request, bindings(request, decision)));
            assertEquals(FeedbackDisposition.NEUTRAL, derived.feedback().disposition());
            assertTrue(derived.feedback().entries().isEmpty());
            assertInstanceOf(Reconciliation.Apply.class, deliver(ledger, first, request, decision));
            ledger.add(AppliedRevision.of(derived.feedback()));

            recordExecution(fixtures, "B", envelope(request("B"), decide(request("B"))),
                    Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            RoutingObservation again = importOf(memory, fixtures, memory.history(SCOPE).highWatermark());

            assertTrue(again.outcome().cutoff() > first.outcome().cutoff());
            assertInstanceOf(Reconciliation.AlreadyApplied.class, deliver(ledger, again, request, decision));
        }
    }
}
