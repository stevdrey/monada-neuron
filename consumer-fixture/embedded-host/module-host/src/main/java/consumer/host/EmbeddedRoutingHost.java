package consumer.host;

import com.monada.api.execution.ExecutionMemory;
import com.monada.core.execution.ScopeId;
import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.host.CycleInput;
import monada.neuron.host.NeuronRuntime;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.perception.PerceptionCapability;
import monada.neuron.perception.PerceptionResult;
import monada.neuron.perception.PerceptionStatus;
import monada.neuron.resonance.adapter.experience.ExecutedRouteDescriptor;
import monada.neuron.resonance.adapter.experience.ExperienceExportRequest;
import monada.neuron.resonance.adapter.experience.ExperienceImport;
import monada.neuron.resonance.adapter.experience.RoutingEnvelope;
import monada.neuron.resonance.adapter.experience.StoreExperienceTranslator;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingCognitiveStageResult;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingInput;
import monada.neuron.routing.RoutingReasoningStage;
import monada.neuron.routing.TrustedRoutingProfile;
import monada.neuron.routing.adaptation.AdaptiveStateDefinition;
import monada.neuron.routing.adaptation.BindResult;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.adaptation.StatePartition;
import monada.neuron.routing.adaptation.StateUpdate;
import monada.neuron.routing.catalog.BillingMode;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.FeedbackDerivation;
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
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * A Java host that embeds the published Neuron and adapter artifacts and owns every piece of routing state
 * explicitly: the scope, the task features, the catalog it passes per request, the fake worker, the acceptance
 * judgment, the Store root and the adaptive routing state. Nothing is a singleton, nothing runs in the background,
 * and the host is sequential: one instance is never used from two threads.
 *
 * <p>The loop composes the existing runtime without changing the cognitive stage order:
 * <ol>
 *   <li><b>route</b> — a routing-only runtime runs {@link RoutingReasoningStage} as the REASONING source of its own
 *       cycle (a routing stage cannot share a cycle with perception); the decision is advice, cross-checked against
 *       the pure Level A call;</li>
 *   <li><b>execute</b> — the host, not Neuron, submits the advised route to its fake worker;</li>
 *   <li><b>delayed result</b> — on a later tick the worker's completion reaches a second runtime through a
 *       {@link PerceptionCapability} and is acknowledged by an {@link ActionCapability} (PERCEPTION then ACTION);</li>
 *   <li><b>record</b> — the host appends the execution and its evidence to the Store and closes it;</li>
 *   <li><b>learn</b> — the host reopens the Store read-only, imports a bounded export through the adapter, derives
 *       quality-gated feedback and consumes it into its caller-owned {@link RoutingStateStore};</li>
 *   <li><b>later recommendation</b> — the next route reads the store's snapshot.</li>
 * </ol>
 */
public final class EmbeddedRoutingHost {

    static final String EVALUATION_POLICY_ID = "forge-gates";
    static final String EVALUATION_POLICY_VERSION = "1";
    static final String SOURCE = "rev-1";
    static final String CONTEXT = "ctx-1";
    static final String CONSTRAINTS = "constraints-1";
    static final String STAGE = "implement";
    static final String ATTEMPT = "a1";

    /** Bound on executed routes the host remembers to attribute exports; equals the export bound below. */
    public static final int MAX_ROUTED = 64;
    /** Export page size and total sample bound handed to the adapter (adapter limits: 500 and 10,000). */
    public static final int EXPORT_PAGE_SIZE = 8;
    public static final int EXPORT_MAX_SAMPLES = MAX_ROUTED;

    private static final TaskFeatures FEATURES = TaskFeatures.builder().stageKind(STAGE).build();
    private static final HardRequirements REQUIREMENTS =
            new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L);
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    /** One routed execution: the request the host issued and the advice Neuron returned. */
    public record Routed(RoutingRequest request, RoutingDecision decision) {
        public RoutingDecision.Selected selected() {
            return (RoutingDecision.Selected) decision;
        }
    }

    /** One delivered observation: what was derived from it and what the state did with it. */
    public record Delivery(RoutingObservation observation, FeedbackDerivation derivation, StateUpdate update) {
    }

    /** One learning pass over a bounded Store export. */
    public record Learning(long cutoff, ExperienceImport imported, List<Delivery> deliveries) {
    }

    private final String scope;
    private final AdaptiveStateDefinition definition;
    private final LexicographicRoutingPolicy policy;
    private final RoutingFeedbackPolicy feedbackPolicy;
    private final RoutingStateStore state;
    private final StoreRecorder recorder;
    private final Path storeRoot;
    private final FakeWorker worker;
    private final NeuronRuntime routingRuntime;
    private final NeuronRuntime deliveryRuntime;

    private final Map<String, RoutingInput> pendingInputs = new HashMap<>();
    private final Map<String, Routed> routed = new LinkedHashMap<>();
    private final Map<String, RoutingEnvelope> envelopes = new HashMap<>();
    private final Map<String, Signal> arrivals = new HashMap<>();
    private final List<String> acknowledged = new ArrayList<>();
    private long watermark;
    private int tick;

    public EmbeddedRoutingHost(Path storeRoot, String scope, FakeWorker worker) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.definition = AdaptiveStateDefinition.reference();
        this.policy = new LexicographicRoutingPolicy(definition.state(),
                LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.empty(), Optional.empty());
        this.feedbackPolicy = definition.feedbackPolicy(new EvidencePolicy(EVALUATION_POLICY_ID,
                EVALUATION_POLICY_VERSION, Set.of(QualityDimension.TESTS, QualityDimension.SECURITY)));
        this.state = new RoutingStateStore(definition,
                new StatePartition(scope, EVALUATION_POLICY_ID, EVALUATION_POLICY_VERSION));
        this.recorder = new StoreRecorder(storeRoot, scope);

        var monad = new PrimaryMonad(new UUID(0L, 68L));
        this.routingRuntime = NeuronRuntime.builder()
                .monad(monad)
                .defaultBudget(new CognitiveBudget(1, 1, 0))
                .stage(new RoutingReasoningStage(policy, TrustedRoutingProfile.lexicographic(policy),
                        context -> Optional.ofNullable(pendingInputs.get(context.executionRef().value())), 0))
                .build();
        PerceptionCapability completions = request -> request.hostContext()
                .map(context -> arrivals.remove(context.executionRef().value()))
                .map(signal -> new PerceptionResult(PerceptionStatus.SUCCEEDED, request.maxSignals(), List.of(signal)))
                .orElseGet(() -> new PerceptionResult(PerceptionStatus.EMPTY, request.maxSignals(), List.of()));
        ActionCapability acknowledge = request -> {
            request.hostContext().ifPresent(context -> acknowledged.add(context.executionRef().value()));
            return new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(), List.of());
        };
        this.deliveryRuntime = NeuronRuntime.builder()
                .monad(monad)
                .defaultBudget(new CognitiveBudget(4, 4, 0))
                .perceptionCapability(completions, 1)
                .actionCapability(acknowledge, 1)
                .build();
    }

    /** Binds one cohort per route of {@code catalog} for this host's task features. */
    public void bind(RouteCatalog catalog) {
        String bucket = definition.state().cohortMapping().bucketOf(FEATURES);
        var keys = catalog.entries().stream()
                .map(entry -> new CohortKey(scope, STAGE, bucket, entry.key()))
                .toList();
        if (!(state.bind(keys) instanceof BindResult.Bound)) {
            throw new IllegalStateException("cohorts could not be bound for " + keys);
        }
    }

    /**
     * Step 1: asks Neuron for advice through the routing-only runtime and cross-checks it against Level A.
     *
     * @param catalog the catalog the host offers for this request (availability is the host's observation)
     */
    public Routed route(String taskId, String executionId, RouteCatalog catalog) {
        var request = new RoutingRequest(RoutingRequest.CONTRACT_VERSION, scope, taskId, executionId, ATTEMPT, STAGE,
                STAGE, 1L, SOURCE, CONTEXT, CONSTRAINTS, EVALUATION_POLICY_ID, EVALUATION_POLICY_VERSION, FEATURES,
                REQUIREMENTS, false, watermark);
        var input = new RoutingInput(request, catalog, state.snapshot(policy, catalog));
        pendingInputs.put(executionId, input);
        CognitiveCycleResult cycle;
        try {
            cycle = routingRuntime.execute(CycleInput.of(List.of()).withHostContext(context(executionId)));
        } finally {
            pendingInputs.remove(executionId);
        }
        var decision = ((RoutingCognitiveStageResult) cycle.stageResults().getFirst()).decision();
        var levelA = policy.decide(request, catalog, input.preference());
        if (!levelA.equals(decision)) {
            throw new IllegalStateException("Level B advice " + decision + " differs from Level A " + levelA);
        }
        return new Routed(request, decision);
    }

    /**
     * Step 2: the host decides to execute the advised route and submits it to its own worker. Only executed advice is
     * remembered for export attribution; advice the host never executes costs nothing.
     */
    public void execute(Routed advice) {
        String executionId = advice.request().executionId();
        var route = advice.selected().route();
        if (routed.containsKey(executionId)) {
            throw new IllegalStateException("execution " + executionId + " was already executed");
        }
        if (routed.size() == MAX_ROUTED) {
            throw new IllegalStateException("the host remembers at most " + MAX_ROUTED + " executed routes");
        }
        worker.submit(executionId, route.routeId());
        routed.put(executionId, advice);
    }

    /**
     * Steps 3 and 4: on a later tick the worker's completion is perceived and acknowledged, then the host records the
     * execution, its envelope reference and its evidence into the Store and closes it.
     *
     * @return the worker report that was recorded
     */
    public WorkerReport completeAndRecord(Routed advice) {
        String executionId = advice.request().executionId();
        WorkerReport report = worker.complete(executionId);
        arrivals.put(executionId, new Signal(SignalKind.OBSERVATION,
                new FrequencyState(report.accepted() ? 1.0 : 0.5, 10.0, 0.0)));
        var delivered = deliveryRuntime.execute(CycleInput.of(List.of()).withHostContext(context(executionId)));
        if (!acknowledged.contains(executionId) || arrivals.containsKey(executionId)) {
            throw new IllegalStateException("the completion of " + executionId + " was not perceived and acknowledged: "
                    + delivered.termination());
        }
        var envelope = envelope(advice);
        String envelopeRef = "env-" + executionId;
        envelopes.put(scope + "/" + envelopeRef, envelope);
        recorder.record(envelope, envelopeRef, report, EPOCH.plusSeconds(60L * ++tick));
        return report;
    }

    /** Negative-case hook: the host's copy of a published envelope changes after the Store recorded its digest. */
    public void tamperEnvelope(String executionId) {
        String key = scope + "/env-" + executionId;
        var original = Objects.requireNonNull(envelopes.get(key), key);
        envelopes.put(key, new RoutingEnvelope(original.contractVersion(), original.decisionRef(), original.route(),
                original.descriptor(), original.catalogVersion(), original.routingPolicyId(),
                original.routingPolicyVersion(), original.evaluationPolicyId(), original.evaluationPolicyVersion(),
                original.cohortBinding(), "tampered"));
    }

    /**
     * Step 5: reopens the Store read-only, imports a bounded export at its current high watermark, and consumes every
     * translated observation. Re-exported samples are idempotent; the watermark is then confirmed.
     */
    public Learning learn() {
        var scopeId = ScopeId.of(scope);
        try (ExecutionMemory memory = ExecutionMemory.openReadOnly(storeRoot, scopeId)) {
            var translator = new StoreExperienceTranslator(memory,
                    (scopeRef, envelopeRef) -> Optional.ofNullable(envelopes.get(scopeRef + "/" + envelopeRef)));
            long cutoff = translator.highWatermark();
            var imported = translator.importSamples(
                    ExperienceExportRequest.of(scope, cutoff, EXPORT_PAGE_SIZE, EXPORT_MAX_SAMPLES));
            var deliveries = new ArrayList<Delivery>(imported.observations().size());
            for (RoutingObservation observation : imported.observations()) {
                var advice = routed.get(observation.outcome().decisionRef().executionId());
                if (advice == null) {
                    throw new IllegalStateException("export holds an execution this host never executed: " + observation);
                }
                deliveries.add(deliver(observation, advice));
            }
            state.advanceCutoff(cutoff);
            watermark = cutoff;
            return new Learning(cutoff, imported, List.copyOf(deliveries));
        }
    }

    /** Derives against this host's own bindings and consumes into this host's state. */
    public Delivery deliver(RoutingObservation observation, Routed advice) {
        var derivation = derive(observation, advice);
        return new Delivery(observation, derivation, state.consume(observation, derivation));
    }

    /** Derives feedback for {@code observation} against this host's own bindings without consuming it. */
    public FeedbackDerivation derive(RoutingObservation observation, Routed advice) {
        return feedbackPolicy.derive(observation, advice.selected(), advice.request(), state.bindings());
    }

    /**
     * An environmental failure as a core observation: the routed invocation timed out, with failing test evidence.
     * The Store's attempt results cannot express {@code TIMED_OUT}/{@code UNAVAILABLE}, so this case is built through
     * the root routing API only (contract §20.4).
     */
    public RoutingObservation timedOut(Routed advice, long ledgerSequence) {
        var request = advice.request();
        var decision = advice.selected();
        var tests = new QualityObservation("timeout-tests", 0, QualityDimension.TESTS, QualityState.FAIL,
                Feature.unknown(), "fake-runner", "1", EVALUATION_POLICY_ID, EVALUATION_POLICY_VERSION,
                EvidenceSource.TOOL, request.attemptId(), ledgerSequence - 1, List.of());
        var outcome = new RoutingOutcome(RoutingRequest.CONTRACT_VERSION, DecisionRef.of(request), decision.route(),
                decision.cohortBinding(), decision.provenance().catalogVersion(), decision.provenance().policyId(),
                decision.provenance().policyVersion(), SOURCE, CONTEXT, CONSTRAINTS, EVALUATION_POLICY_ID,
                EVALUATION_POLICY_VERSION, Math.max(watermark, ledgerSequence), InvocationStatus.TIMED_OUT,
                OutcomeStatus.FAILED, OutcomeOrigin.VALIDATED, Feature.unknown(), Feature.unknown(), ledgerSequence,
                List.of(tests), List.of(), ResourceSummary.unrecorded(),
                new StageAttribution(StageAttribution.Basis.STAGE_LOCAL, 1,
                        List.of(new EvidenceRef("log", "timeout-" + request.executionId(), Feature.unknown()))));
        return new RoutingObservation(outcome, "timeout-" + request.executionId(), 0, ledgerSequence,
                Feature.unknown());
    }

    /** The caller-owned adaptive state, for inspection. */
    public RoutingStateStore state() {
        return state;
    }

    /** The policy this host routes with. */
    public LexicographicRoutingPolicy policy() {
        return policy;
    }

    /** Executions whose delayed completion was perceived and acknowledged, in order. */
    public List<String> acknowledged() {
        return List.copyOf(acknowledged);
    }

    private static HostExecutionContext context(String executionId) {
        return HostExecutionContext.of(new HostReference(executionId));
    }

    /** The envelope the host publishes for one routed stage: every field is the decision's own. */
    private static RoutingEnvelope envelope(Routed advice) {
        var decision = advice.selected();
        var provenance = decision.provenance();
        RouteKey route = decision.route();
        return new RoutingEnvelope(RoutingRequest.CONTRACT_VERSION, DecisionRef.of(advice.request()), route,
                new ExecutedRouteDescriptor("worker-" + route.routeId(), Feature.known("fake"),
                        Feature.known("model-" + route.routeId()), Feature.known("standard"),
                        BillingMode.SUBSCRIPTION),
                provenance.catalogVersion(), provenance.policyId(), provenance.policyVersion(), EVALUATION_POLICY_ID,
                EVALUATION_POLICY_VERSION, Feature.known(decision.cohortBinding()), decision.executionMode());
    }
}
