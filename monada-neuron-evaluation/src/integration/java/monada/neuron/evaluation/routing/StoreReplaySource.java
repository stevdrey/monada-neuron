package monada.neuron.evaluation.routing;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExportCutoff;
import com.monada.api.execution.ExportEvidencePolicy;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.api.execution.RecordResult;
import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.PriceLine;
import com.monada.core.execution.PriceSnapshot;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageKind;
import com.monada.evaluation.execution.AnnotatedSample;
import com.monada.evaluation.execution.CostReason;
import com.monada.evaluation.execution.CostToAcceptedOutcome;
import com.monada.evaluation.execution.CurrencyCost;
import com.monada.evaluation.execution.ExperienceExportReport;
import com.monada.evaluation.execution.PriceBook;
import monada.neuron.resonance.adapter.experience.ExecutedRouteDescriptor;
import monada.neuron.resonance.adapter.experience.ExperienceExportRequest;
import monada.neuron.resonance.adapter.experience.ExperienceImport;
import monada.neuron.resonance.adapter.experience.RoutingEnvelope;
import monada.neuron.resonance.adapter.experience.StoreExperienceTranslator;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.catalog.BillingMode;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.outcome.Money;
import monada.neuron.routing.outcome.RoutingObservation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Records a deterministic logged history into a real, temporary Resonance Store execution memory and exports it as a
 * chosen-route-only {@link ReplayFixture}.
 *
 * <p>The logging policy is the #62 static rule: each task is decided at the Store's high-watermark before it is
 * recorded, its routing envelope is published, and the execution is recorded with the routed stage's usage, the
 * judged evidence and the outcome. Truth: {@code sub-b} passes, {@code sub-a} fails tests, and a four-task
 * {@code sub-a} outage opens the log. The last execution never finishes, so it has no exportable outcome. Everything
 * else is read back through the public paths a host would use: {@link StoreExperienceTranslator} for the neutral observations and
 * the Store's {@link ExperienceExportReport} (Store #99) for each execution's {@code CostToAcceptedOutcome}, priced at
 * the hand-calculable list prices below. Ids and timestamps are caller-supplied; nothing reads a clock.
 */
final class StoreReplaySource implements AutoCloseable {

    static final String WORKER = "replay-worker";
    static final String PROVIDER = "fixture";
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final AttemptId A1 = AttemptId.of("a1");
    /** List prices per 1000 tokens, shared with the synthetic fixtures: input 0.015 USD, output 0.080 USD. */
    static final BigDecimal INPUT_PER_THOUSAND = SyntheticRoutingFixtures.INPUT_PER_THOUSAND;
    static final BigDecimal OUTPUT_PER_THOUSAND = SyntheticRoutingFixtures.OUTPUT_PER_THOUSAND;

    private final Path root;
    private final ExecutionMemory memory;
    private final ScopeId scope = ScopeId.of(SyntheticRoutingFixtures.SCOPE);
    private final Map<EnvelopeKey, RoutingEnvelope> envelopes = new HashMap<>();
    private int tick;
    private int counter;

    private StoreReplaySource(Path root) {
        this.root = root;
        this.memory = ExecutionMemory.open(root, scope);
    }

    /** Opens an empty execution memory in a new temporary directory. */
    static StoreReplaySource open() {
        try {
            Path root = Files.createTempDirectory("monada-neuron-routing-replay-");
            try {
                return new StoreReplaySource(root);
            } catch (RuntimeException failure) {
                // a failed cleanup must not hide why opening failed
                try {
                    delete(root);
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create a temporary execution memory", e);
        }
    }

    /**
     * What one recording and export produced.
     *
     * @param fixture the chosen-route-only fixture
     * @param imported the translator's import, with its rejections and diagnostics
     * @param costs the Store's cost per execution id, as reported by its evaluation tooling
     * @param exportDigest the Store report's export digest
     * @param cutoff the export cutoff (the Store's final high-watermark)
     */
    record Export(ReplayFixture fixture, ExperienceImport imported, Map<String, CostToAcceptedOutcome> costs,
            String exportDigest, long cutoff) {
    }

    /** Records {@code tasks} executions and exports them. */
    Export recordAndExport(int tasks) {
        var logged = new ArrayList<Logged>(tasks);
        for (int i = 0; i < tasks; i++) {
            RouteCatalog catalog = i < 4 ? SyntheticRoutingFixtures.SUB_A_OUTAGE : SyntheticRoutingFixtures.FULL;
            RoutingRequest request = request(i, memory.history(scope).highWatermark());
            var decision = (RoutingDecision.Selected) SyntheticRoutingFixtures.POLICY.decide(request, catalog,
                    RoutingPreference.empty(request, SyntheticRoutingFixtures.DEFINITION.state(),
                            SyntheticRoutingFixtures.POLICY.policyId(),
                            SyntheticRoutingFixtures.POLICY.policyVersion()));
            record(request, decision, i == tasks - 1);
            logged.add(new Logged(request, catalog, decision));
        }
        long cutoff = memory.history(scope).highWatermark();
        ExperienceImport imported = new StoreExperienceTranslator(memory,
                (scopeId, ref) -> Optional.ofNullable(envelopes.get(new EnvelopeKey(scopeId, ref))))
                .importSamples(ExperienceExportRequest.of(scope.value(), cutoff, 100, 1000));
        ExperienceExportReport report = ExperienceExportReport.build(memory,
                ExportRequest.first(scope, ExportCutoff.atSequence(cutoff), 100, ExportView.ADAPTER_READY,
                        ExportEvidencePolicy.executionPolicyOnly()),
                PriceBook.of(List.of(prices("sub-a"), prices("sub-b"))), Optional.empty());
        var costs = new HashMap<String, CostToAcceptedOutcome>();
        var attempts = new HashMap<String, Integer>();
        for (AnnotatedSample sample : report.samples()) {
            costs.put(sample.sample().executionId().value(), sample.cost());
            attempts.put(sample.sample().executionId().value(), sample.sample().attempts().size());
        }
        var replayTasks = new ArrayList<ReplayTask>(tasks);
        for (Logged log : logged) {
            String execution = log.request().executionId();
            List<RoutingObservation> observed = imported.observations().stream()
                    .filter(o -> o.outcome().decisionRef().equals(DecisionRef.of(log.request()))).toList();
            ChainOutcome chain = costs.containsKey(execution)
                    ? new ChainOutcome(Math.max(1, attempts.get(execution)), replayCost(costs.get(execution)))
                    : new ChainOutcome(1, ReplayCost.unavailable("NOT_EXPORTED"));
            replayTasks.add(new ReplayTask(log.request(), log.catalog(),
                    new OutcomeOracle.ChosenRouteOnly(log.decision(), observed, chain)));
        }
        var fixture = new ReplayFixture(SyntheticRoutingFixtures.VERSION + ".store-export", "1",
                SyntheticRoutingFixtures.DEFINITION, SyntheticRoutingFixtures.EVIDENCE,
                SyntheticRoutingFixtures.POLICY, SyntheticRoutingFixtures.PARTITION, replayTasks, cutoff);
        return new Export(fixture, imported, Map.copyOf(costs), report.exportDigest(), cutoff);
    }

    /** Translates the Store's cost into the replay's mirror: same status, one rounded amount per currency. */
    static ReplayCost replayCost(CostToAcceptedOutcome cost) {
        List<Money> amounts = cost.amounts().stream()
                .sorted(Comparator.comparing((CurrencyCost c) -> c.currency().getCurrencyCode()))
                .map(c -> new Money(c.amount(), c.currency().getCurrencyCode())).toList();
        List<String> reasons = cost.reasons().stream().map(CostReason::code).map(Enum::name).distinct().toList();
        return new ReplayCost(switch (cost.status()) {
            case AVAILABLE -> ReplayCost.Status.AVAILABLE;
            case PARTIAL -> ReplayCost.Status.PARTIAL;
            case UNAVAILABLE -> ReplayCost.Status.UNAVAILABLE;
        }, amounts, reasons);
    }

    /** Whether the temporary directory still exists. */
    boolean exists() {
        return Files.exists(root);
    }

    /**
     * Closes the memory and deletes the temporary directory, attempting the deletion even when closing fails; the first
     * failure is thrown with any later one attached as suppressed.
     */
    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            memory.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            delete(root);
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** A routing envelope's address: its scope and artifact reference, compared component by component. */
    private record EnvelopeKey(String scopeId, String ref) {
    }

    private void record(RoutingRequest request, RoutingDecision.Selected decision, boolean unfinished) {
        boolean passes = decision.route().equals(SyntheticRoutingFixtures.SUB_B);
        var task = TaskId.of(request.taskId());
        var execution = ExecutionId.of(request.executionId());
        append(ExecutionEvent.executionStarted(id("start"), scope, task, execution,
                new SourceProvenance(request.sourceFingerprint(), request.contextFingerprint(),
                        request.constraintsFingerprint()),
                new EvaluationPolicy(request.evaluationPolicyId(), request.evaluationPolicyVersion(),
                        Set.of(QualityDimension.TESTS, QualityDimension.SECURITY)),
                "Replay " + request.executionId(), advance()));
        Instant started = advance();
        append(new AttemptStarted(EventEnvelope.original(id("att"), scope, task, execution, A1, started, started), 1,
                Optional.empty(), AttemptReason.INITIAL));

        String model = decision.route().routeId();
        var envelope = new RoutingEnvelope(RoutingRequest.CONTRACT_VERSION, DecisionRef.of(request), decision.route(),
                new ExecutedRouteDescriptor(WORKER, Feature.known(PROVIDER), Feature.known(model), Feature.unknown(),
                        BillingMode.SUBSCRIPTION),
                decision.provenance().catalogVersion(), decision.provenance().policyId(),
                decision.provenance().policyVersion(), request.evaluationPolicyId(),
                request.evaluationPolicyVersion(), Feature.known(decision.cohortBinding()), decision.executionMode());
        Instant t = advance();
        EventId stage = id("stage");
        String ref = "env-" + stage.value();
        envelopes.put(new EnvelopeKey(scope.value(), ref), envelope);
        long input = SyntheticRoutingFixtures.inputTokens(decision.route());
        long output = SyntheticRoutingFixtures.outputTokens(decision.route());
        append(StageRecorded.measured(EventEnvelope.original(stage, scope, task, execution, A1, t, t),
                request.stageId(), new RouteDescriptor(WORKER, Optional.of(PROVIDER),
                        Optional.of(model), Optional.empty(), com.monada.core.execution.BillingMode.SUBSCRIPTION),
                t, t, List.of(UsageCounter.reported(UsageKind.INPUT_TOKENS, input, PROVIDER),
                        UsageCounter.reported(UsageKind.OUTPUT_TOKENS, output, PROVIDER)),
                List.of(ArtifactRef.of(RoutingEnvelope.ARTIFACT_KIND, ref, envelope.digest()))));
        if (unfinished) {
            return;
        }
        Instant judged = advance();
        append(new EvidenceRecorded(EventEnvelope.original(id("ev"), scope, task, execution, A1, judged, judged),
                List.of(observation(QualityDimension.TESTS, passes ? ObservationState.PASS : ObservationState.FAIL),
                        observation(QualityDimension.SECURITY, ObservationState.PASS)), List.of()));
        Instant finished = advance();
        append(new AttemptFinished(EventEnvelope.original(id("fin"), scope, task, execution, A1, finished, finished),
                AttemptResult.COMPLETED, Optional.of("solution"), Optional.of("lesson")));
        append(ExecutionEvent.outcomeRecorded(id("out"), scope, task, execution,
                passes ? Outcome.accepted(OutcomeOrigin.VALIDATED, A1) : Outcome.rejected(OutcomeOrigin.VALIDATED),
                advance()));
    }

    private QualityObservation observation(QualityDimension dimension, ObservationState state) {
        return new QualityObservation(dimension, state, OptionalDouble.empty(), Optional.empty(),
                ObservationFactory.JUDGE, "1", SyntheticRoutingFixtures.EVIDENCE.id(),
                SyntheticRoutingFixtures.EVIDENCE.version(), ObservationSource.TOOL,
                List.of(ArtifactRef.of("report", "r-" + dimension)), Optional.empty());
    }

    private PriceSnapshot prices(String model) {
        return new PriceSnapshot("replay-prices-" + model, "1", PROVIDER, model, PriceSnapshot.currency("USD"),
                LocalDate.of(2026, 1, 1), "fixture", "hand-calculable replay list prices",
                List.of(PriceLine.of(UsageKind.INPUT_TOKENS, INPUT_PER_THOUSAND, 1000),
                        PriceLine.of(UsageKind.OUTPUT_TOKENS, OUTPUT_PER_THOUSAND, 1000)));
    }

    private RoutingRequest request(int i, long cutoff) {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, SyntheticRoutingFixtures.SCOPE, "task-" + i,
                "exe-" + i, A1.value(), "stage-implement", SyntheticRoutingFixtures.IMPLEMENT, i, "src-1", "ctx-1",
                "con-1", SyntheticRoutingFixtures.EVIDENCE.id(), SyntheticRoutingFixtures.EVIDENCE.version(),
                TaskFeatures.builder().stageKind(SyntheticRoutingFixtures.IMPLEMENT).changeSize(120L).build(),
                HardRequirements.allowing(List.of("hosted"), List.of("sandboxed")), false, cutoff);
    }

    private void append(ExecutionEvent event) {
        RecordResult result = memory.record(event);
        if (result.status() != RecordResult.Status.APPENDED) {
            throw new IllegalStateException("the Store refused " + event + ": " + result.reason());
        }
    }

    private Instant advance() {
        return T0.plusSeconds(++tick);
    }

    private EventId id(String kind) {
        return EventId.of(kind + "-" + (++counter));
    }

    private static void delete(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + root, e);
        }
    }

    private record Logged(RoutingRequest request, RouteCatalog catalog, RoutingDecision.Selected decision) {
    }
}
