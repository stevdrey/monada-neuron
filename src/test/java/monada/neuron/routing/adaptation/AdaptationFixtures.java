package monada.neuron.routing.adaptation;

import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.model.NodeType;
import monada.neuron.routing.ChangeSizeCohortMapping;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.BillingMode;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.RoutingFeedbackConfig;
import monada.neuron.routing.feedback.RoutingFeedbackPolicy;
import monada.neuron.routing.outcome.EvidencePolicy;
import monada.neuron.routing.outcome.EvidenceRef;
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

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Fictional requests, decisions and observations built through the public API only; no provider or Store type. */
final class AdaptationFixtures {

    /** Hand-calculable scores: reward 0.8, penalty -0.4, no cost refinement. */
    static final RoutingFeedbackConfig SCORES = new RoutingFeedbackConfig(0.8, -0.4, 0.0, Optional.empty());

    /** Learning rate 0.5 and energy step 2: a reward moves 4.0 / 10.0 to 5.6 / 10.8, a penalty to 3.2 / 9.6. */
    static final AdaptationConfig ADAPTATION = new AdaptationConfig(0.5, 0.5, 6.0, 0.0, 12.0, 0.0, 10_000.0, 2.0);

    static final InitialNodeState INITIAL = new InitialNodeState(4.0, 50.0, 0.5, 10.0, NodeType.PROCESSOR);

    static final AdaptiveStateDefinition DEFINITION = definition("adaptive-test/1");

    static final EvidencePolicy GATES =
            new EvidencePolicy("forge-gates", "1", Set.of(QualityDimension.TESTS, QualityDimension.SECURITY));

    static final StatePartition PARTITION = new StatePartition("scope-demo", "forge-gates", "1");

    static final RouteKey SUB_A = new RouteKey("sub-a", 1);
    static final RouteKey SUB_B = new RouteKey("sub-b", 1);
    static final RouteKey API_X = new RouteKey("api-x", 1);

    /** Large enough for long fixtures; equal for requests and outcomes. */
    static final long CUTOFF = 1_000_000L;

    /** How an observation turns out. */
    enum Kind { PASS, FAIL, PENDING }

    private AdaptationFixtures() {
    }

    static AdaptiveStateDefinition definition(String mappingVersion) {
        return new AdaptiveStateDefinition(
                new RoutingStateDefinition(mappingVersion, ChangeSizeCohortMapping.DEFAULT), INITIAL, ADAPTATION,
                SCORES);
    }

    static RoutingStateStore store() {
        return new RoutingStateStore(DEFINITION, PARTITION);
    }

    static LexicographicRoutingPolicy policy(AdaptiveStateDefinition definition) {
        return new LexicographicRoutingPolicy(definition.state(), 3, OptionalInt.empty(), Optional.empty());
    }

    static LexicographicRoutingPolicy policy() {
        return policy(DEFINITION);
    }

    static RoutingFeedbackPolicy feedbackPolicy() {
        return DEFINITION.feedbackPolicy(GATES);
    }

    static RouteDescriptor route(String id) {
        return RouteDescriptor.builder(id, 1).billingMode(BillingMode.SUBSCRIPTION)
                .stages(List.of("plan", "implement", "review", "qa")).executionModes(List.of("sandboxed"))
                .locality("hosted").build();
    }

    /** {@code sub-a} and {@code sub-b}: same tier and fallback priority, so only learning or route order decides. */
    static RouteCatalog demo() {
        return RouteCatalog.of("cat-demo-7", List.of(new CatalogEntry(route("sub-a"), Availability.AVAILABLE, 1),
                new CatalogEntry(route("sub-b"), Availability.AVAILABLE, 1)));
    }

    static RouteCatalog only(String routeId) {
        return RouteCatalog.of("cat-" + routeId,
                List.of(new CatalogEntry(route(routeId), Availability.AVAILABLE, 1)));
    }

    static RoutingRequest request(String scope, String stageKind, Feature<Long> changeSize, String execution,
            long ordinal) {
        var features = TaskFeatures.builder().stageKind(stageKind);
        if (changeSize instanceof Feature.Known<Long> known) {
            features.changeSize(known.value());
        }
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, scope, "task-17", execution, "att-1",
                "s-" + execution, stageKind, ordinal, "src-1", "ctx-1", "con-1", "forge-gates", "1",
                features.build(), new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L),
                false, CUTOFF);
    }

    static RoutingRequest request(String execution) {
        return request("scope-demo", "implement", Feature.unknown(), execution, 101L);
    }

    /** A {@code Selected} decision over a catalog that holds only {@code routeId}, so that route is advised. */
    static RoutingDecision.Selected decide(RoutingRequest request, String routeId, AdaptiveStateDefinition definition) {
        var policy = policy(definition);
        var empty = RoutingPreference.empty(request, definition.state(), policy.policyId(), policy.policyVersion());
        return (RoutingDecision.Selected) policy.decide(request, only(routeId), empty);
    }

    static RoutingDecision.Selected decide(RoutingRequest request, String routeId) {
        return decide(request, routeId, DEFINITION);
    }

    static CohortKey key(RoutingRequest request, RoutingDecision.Selected decision) {
        return new CohortKey(request.scopeId(), request.stageKind(), decision.cohortBinding().cohortBucket(),
                decision.route());
    }

    /** One executed sample of {@code decision}; {@code supersedes} is required exactly for {@code revision > 0}. */
    static RoutingObservation observation(RoutingRequest request, RoutingDecision.Selected decision, String ref,
            long revision, long ledgerSequence, Feature<Long> supersedes, Kind kind) {
        return observation(request, decision, ref, revision, ledgerSequence, supersedes, kind,
                ResourceSummary.unrecorded());
    }

    /** As above, with the routed stage's measured usage, so a cost refinement can compare two executions. */
    static RoutingObservation observation(RoutingRequest request, RoutingDecision.Selected decision, String ref,
            long revision, long ledgerSequence, Feature<Long> supersedes, Kind kind, ResourceSummary resources) {
        String attempt = request.attemptId();
        QualityState tests = kind == Kind.FAIL ? QualityState.FAIL : QualityState.PASS;
        var observations = List.of(
                quality("ev-tests", QualityDimension.TESTS, tests, attempt, 5),
                quality("ev-sec", QualityDimension.SECURITY, QualityState.PASS, attempt, 6));
        OutcomeStatus status = switch (kind) {
            case PASS -> OutcomeStatus.ACCEPTED;
            case FAIL -> OutcomeStatus.REJECTED;
            case PENDING -> OutcomeStatus.PENDING;
        };
        Feature<String> accepted = status == OutcomeStatus.ACCEPTED ? Feature.known(attempt) : Feature.unknown();
        var outcome = new RoutingOutcome(RoutingRequest.CONTRACT_VERSION, DecisionRef.of(request), decision.route(),
                decision.cohortBinding(), decision.provenance().catalogVersion(), decision.provenance().policyId(),
                decision.provenance().policyVersion(), "src-1", "ctx-1", "con-1", "forge-gates", "1", CUTOFF,
                InvocationStatus.COMPLETED, status, OutcomeOrigin.VALIDATED, accepted, Feature.unknown(), 10L,
                observations, List.of(), resources,
                new StageAttribution(StageAttribution.Basis.STAGE_LOCAL, 1,
                        List.of(new EvidenceRef("log", "ref-stage", Feature.unknown()))));
        return new RoutingObservation(outcome, ref, revision, ledgerSequence, supersedes);
    }

    /** Measured input tokens of the routed stage. */
    static ResourceSummary inputTokens(long tokens) {
        return new ResourceSummary(UsageDeclaration.MEASURED, Feature.unknown(),
                List.of(new UsageCounter(UsageKind.INPUT_TOKENS,
                        new ResourceValue.Known(tokens, ResourceValue.Provenance.REPORTED))),
                List.of(), List.of());
    }

    static RoutingObservation observation(RoutingRequest request, RoutingDecision.Selected decision, String ref,
            long ledgerSequence, Kind kind) {
        return observation(request, decision, ref, 0, ledgerSequence, Feature.unknown(), kind);
    }

    private static QualityObservation quality(String event, QualityDimension dimension, QualityState state,
            String attempt, long sequence) {
        return new QualityObservation(event, 0, dimension, state, Feature.unknown(), "runner", "1", "forge-gates", "1",
                EvidenceSource.TOOL, attempt, sequence, List.of());
    }

    /** Derives against the store's own bindings, as a caller does. */
    static FeedbackDerivation derive(RoutingStateStore store, RoutingObservation observation,
            RoutingDecision.Selected decision, RoutingRequest request) {
        return store.definition().feedbackPolicy(GATES).derive(observation, decision, request, store.bindings());
    }

    /** Derives and consumes in one step. */
    static StateUpdate deliver(RoutingStateStore store, RoutingObservation observation,
            RoutingDecision.Selected decision, RoutingRequest request) {
        return store.consume(observation, derive(store, observation, decision, request));
    }
}
