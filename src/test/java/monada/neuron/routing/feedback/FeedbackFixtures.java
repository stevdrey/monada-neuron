package monada.neuron.routing.feedback;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.ResourceObjective;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.outcome.AttemptStatus;
import monada.neuron.routing.outcome.EvidencePolicy;
import monada.neuron.routing.outcome.EvidenceRef;
import monada.neuron.routing.outcome.EvidenceRevision;
import monada.neuron.routing.outcome.EvidenceSource;
import monada.neuron.routing.outcome.InvocationStatus;
import monada.neuron.routing.outcome.OutcomeOrigin;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.QualityObservation;
import monada.neuron.routing.outcome.QualityState;
import monada.neuron.routing.outcome.ResourceSummary;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;
import monada.neuron.routing.outcome.StageAttribution;
import monada.neuron.routing.outcome.UsageCounter;
import monada.neuron.routing.outcome.UsageDeclaration;
import monada.neuron.routing.outcome.UsageKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Fictional requests, decisions, outcomes and bindings; no provider, credential or Store type. */
final class FeedbackFixtures {

    /** Hand-calculable scores: reward 0.8, penalty -0.4, cost bonus 0.2 (refined reward 1.0). */
    static final RoutingFeedbackConfig CONFIG = new RoutingFeedbackConfig(0.8, -0.4, 0.2,
            Optional.of(new CostObjective(UsageKind.INPUT_TOKENS, ResourceObjective.Direction.MINIMIZE)));
    static final RoutingFeedbackConfig NO_COST = new RoutingFeedbackConfig(0.8, -0.4, 0.0, Optional.empty());
    static final EvidencePolicy GATES =
            new EvidencePolicy("forge-gates", "1", Set.of(QualityDimension.TESTS, QualityDimension.SECURITY));
    static final RoutingStateDefinition DEFINITION = LexicographicRoutingPolicy.reference().definition();

    static final UUID NODE_A = new UUID(0L, 1L);
    static final UUID NODE_B = new UUID(0L, 2L);

    private FeedbackFixtures() {
    }

    static RoutingFeedbackPolicy policy() {
        return new RoutingFeedbackPolicy(DEFINITION, CONFIG, GATES);
    }

    static RoutingRequest request(String scope, String task, String execution, String attempt, String stage,
            long ordinal) {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, scope, task, execution, attempt, stage,
                "implement", ordinal, "src-1", "ctx-1", "con-1", "forge-gates", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false, 100L);
    }

    static RoutingRequest request() {
        return request("scope-demo", "task-17", "exec-1", "att-1", "s-impl", 101L);
    }

    /** A {@code Selected} decision for a catalog that holds only {@code routeId}, so that route is the winner. */
    static RoutingDecision.Selected decide(RoutingRequest request, String routeId) {
        var descriptor = RouteDescriptor.builder(routeId, 1).stages(List.of("implement"))
                .executionModes(List.of("sandboxed")).locality("hosted").build();
        var catalog = RouteCatalog.of("cat-demo-7", List.of(new CatalogEntry(descriptor, Availability.AVAILABLE, 1)));
        var policy = LexicographicRoutingPolicy.reference();
        var empty = RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion());
        return (RoutingDecision.Selected) policy.decide(request, catalog, empty);
    }

    static CohortKey keyOf(RoutingDecision.Selected decision, RoutingRequest request) {
        return new CohortKey(request.scopeId(), request.stageKind(), decision.cohortBinding().cohortBucket(),
                decision.route());
    }

    static TargetBindings bindings(RoutingDecision.Selected decision, RoutingRequest request, UUID node) {
        return new TargetBindings(request.scopeId(), request.evaluationPolicyId(), request.evaluationPolicyVersion(),
                DEFINITION.mappingVersion(), List.of(new TargetBinding(keyOf(decision, request), node)));
    }

    static QualityObservation obs(String event, long revision, QualityDimension dimension, QualityState state,
            String attempt, long sequence) {
        return new QualityObservation(event, revision, dimension, state, Feature.unknown(), "runner", "1",
                "forge-gates", "1", EvidenceSource.TOOL, attempt, sequence, List.of());
    }

    static StageAttribution stageLocal() {
        return new StageAttribution(StageAttribution.Basis.STAGE_LOCAL, 1,
                List.of(new EvidenceRef("log", "ref-stage", Feature.unknown())));
    }

    static ResourceSummary tokens(long inputTokens, ResourceValue.Provenance provenance) {
        return new ResourceSummary(UsageDeclaration.MEASURED, Feature.unknown(),
                List.of(new UsageCounter(UsageKind.INPUT_TOKENS, new ResourceValue.Known(inputTokens, provenance))),
                List.of(), List.of());
    }

    /** Mutable builder of an observation that answers {@code decision}; defaults to validated, stage-local. */
    static final class Spec {
        final RoutingRequest request;
        final RoutingDecision.Selected decision;
        String experienceRef = "exp-1";
        long revision = 0;
        long ledgerSequence = 12L;
        long cutoff = 100L;
        Feature<Long> supersedes = Feature.unknown();
        InvocationStatus invocation = InvocationStatus.COMPLETED;
        OutcomeStatus status = OutcomeStatus.ACCEPTED;
        OutcomeOrigin origin = OutcomeOrigin.VALIDATED;
        Feature<AttemptStatus> acceptedAttemptStatus = Feature.unknown();
        /** Attempt the outcome accepted; defaults to the routed attempt. */
        String acceptedAttempt;
        RouteKey executed;
        String sourceFingerprint = "src-1";
        List<QualityObservation> observations = new ArrayList<>();
        List<EvidenceRevision> judged = new ArrayList<>();
        ResourceSummary resources = ResourceSummary.unrecorded();
        StageAttribution attribution = stageLocal();

        Spec() {
            this(request(), decide(request(), "sub-a"));
        }

        Spec(RoutingRequest request, RoutingDecision.Selected decision) {
            this.request = request;
            this.decision = decision;
            this.executed = decision.route();
        }

        Spec passing() {
            String attempt = request.attemptId();
            observations.add(obs("ev-tests", 0, QualityDimension.TESTS, QualityState.PASS, attempt, 5));
            observations.add(obs("ev-sec", 0, QualityDimension.SECURITY, QualityState.PASS, attempt, 6));
            return this;
        }

        Spec failing() {
            String attempt = request.attemptId();
            observations.add(obs("ev-tests", 0, QualityDimension.TESTS, QualityState.FAIL, attempt, 5));
            observations.add(obs("ev-sec", 0, QualityDimension.SECURITY, QualityState.PASS, attempt, 6));
            status = OutcomeStatus.REJECTED;
            return this;
        }

        Spec with(QualityObservation observation) {
            observations.add(observation);
            return this;
        }

        RoutingObservation observation() {
            Feature<String> accepted = status == OutcomeStatus.ACCEPTED
                    ? Feature.known(acceptedAttempt != null ? acceptedAttempt : request.attemptId()) : Feature.unknown();
            var outcome = new RoutingOutcome(RoutingRequest.CONTRACT_VERSION, DecisionRef.of(request), executed,
                    decision.cohortBinding(), decision.provenance().catalogVersion(),
                    decision.provenance().policyId(), decision.provenance().policyVersion(), sourceFingerprint,
                    "ctx-1", "con-1", "forge-gates", "1", cutoff, invocation, status, origin, accepted,
                    acceptedAttemptStatus, 10L, observations, judged, resources, attribution);
            return new RoutingObservation(outcome, experienceRef, revision, ledgerSequence, supersedes);
        }
    }
}
