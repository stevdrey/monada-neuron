package monada.neuron.routing.outcome;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Fictional decision, outcome and evidence fixtures; no provider, credential or Store type. */
final class OutcomeFixtures {

    static final EvidencePolicy GATES =
            new EvidencePolicy("forge-gates", "1", Set.of(QualityDimension.TESTS, QualityDimension.SECURITY));
    static final EvidencePolicy TESTS_ONLY =
            new EvidencePolicy("forge-gates", "1", Set.of(QualityDimension.TESTS));

    static final RoutingRequest REQUEST = request();
    static final RoutingDecision.Selected DECISION = decide(REQUEST);

    private OutcomeFixtures() {
    }

    private static RoutingRequest request() {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope-demo", "task-17", "exec-1", "att-1",
                "s-impl", "implement", 101L, "src-1", "ctx-1", "con-1", "forge-gates", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false, 100L);
    }

    private static RoutingDecision.Selected decide(RoutingRequest request) {
        var descriptor = RouteDescriptor.builder("sub-a", 1).stages(List.of("implement"))
                .executionModes(List.of("sandboxed")).locality("hosted").build();
        var catalog = RouteCatalog.of("cat-demo-7", List.of(new CatalogEntry(descriptor, Availability.AVAILABLE, 1)));
        var policy = LexicographicRoutingPolicy.reference();
        var preference = RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion());
        return (RoutingDecision.Selected) policy.decide(request, catalog, preference);
    }

    static QualityObservation obs(String event, long revision, QualityDimension dimension, QualityState state,
            String attempt, long sequence) {
        return obs(event, revision, dimension, state, "1", attempt, sequence);
    }

    static QualityObservation obs(String event, long revision, QualityDimension dimension, QualityState state,
            String policyVersion, String attempt, long sequence) {
        return new QualityObservation(event, revision, dimension, state,
                state == QualityState.NOT_APPLICABLE ? Feature.known("no network access in this stage") : Feature.unknown(),
                "runner", "1", "forge-gates", policyVersion, EvidenceSource.TOOL, attempt, sequence, List.of());
    }

    static StageAttribution stageLocal() {
        return new StageAttribution(StageAttribution.Basis.STAGE_LOCAL, 1,
                List.of(new EvidenceRef("log", "ref-stage", Feature.unknown())));
    }

    /** Mutable test builder for an outcome that matches {@link #DECISION} by default. */
    static final class Spec {
        InvocationStatus invocation = InvocationStatus.COMPLETED;
        OutcomeStatus status = OutcomeStatus.ACCEPTED;
        OutcomeOrigin origin = OutcomeOrigin.VALIDATED;
        Feature<String> acceptedAttempt = Feature.known("att-1");
        long outcomeSequence = 10L;
        long cutoff = 100L;
        RouteKey route = DECISION.route();
        String catalogVersion = DECISION.provenance().catalogVersion();
        String sourceFingerprint = "src-1";
        String evaluationPolicyVersion = "1";
        List<QualityObservation> observations = new ArrayList<>();
        List<EvidenceRevision> judged = new ArrayList<>();
        ResourceSummary resources = ResourceSummary.unrecorded();
        StageAttribution attribution = stageLocal();

        Spec passing() {
            observations.add(obs("ev-tests", 0, QualityDimension.TESTS, QualityState.PASS, "att-1", 5));
            observations.add(obs("ev-sec", 0, QualityDimension.SECURITY, QualityState.PASS, "att-1", 6));
            return this;
        }

        Spec with(QualityObservation observation) {
            observations.add(observation);
            return this;
        }

        RoutingOutcome outcome() {
            return new RoutingOutcome(RoutingRequest.CONTRACT_VERSION, DecisionRef.of(REQUEST), route,
                    DECISION.cohortBinding(), catalogVersion, DECISION.provenance().policyId(),
                    DECISION.provenance().policyVersion(), sourceFingerprint, "ctx-1", "con-1", "forge-gates",
                    evaluationPolicyVersion, cutoff, invocation, status, origin, acceptedAttempt, outcomeSequence,
                    observations, judged, resources, attribution);
        }

        RoutingObservation observation() {
            return new RoutingObservation(outcome(), "exp-1", 0, 12L, Feature.unknown());
        }

        LearningAssessment assess() {
            return assess(GATES);
        }

        LearningAssessment assess(EvidencePolicy policy) {
            return new OutcomeEvaluator().assess(observation(), DECISION, REQUEST, policy);
        }
    }
}
