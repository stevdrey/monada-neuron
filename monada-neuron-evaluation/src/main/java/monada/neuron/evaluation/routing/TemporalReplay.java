package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.routing.ReplayRun.Delivered;
import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.adaptation.BindResult;
import monada.neuron.routing.adaptation.RebuildResult;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.adaptation.StateUpdate;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.RebuildOrder;
import monada.neuron.routing.feedback.RoutingFeedback;
import monada.neuron.routing.feedback.RoutingFeedbackPolicy;
import monada.neuron.routing.outcome.LearningAssessment;
import monada.neuron.routing.outcome.OutcomeEvaluator;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Chronological, leakage-free replay of one {@link ReplayFixture} under one {@link ReplayArm}.
 *
 * <p>Tasks are decided in {@link ReplayTask#ORDER}. The cutoff is inclusive (routing contract, "Ordinals and
 * cutoff"): before the decision at cutoff {@code c}, exactly the pending observations with {@code ledgerSequence <= c}
 * are delivered, in {@code (ledgerSequence, experienceRef, revision)} order, and an outcome, acceptance, correction or
 * usage observed after {@code c} cannot reach that decision. A decision's own outcome is visible strictly after its
 * cutoff ({@link ReplayTask}), so it is never delivered before the decision. After the last decision every remaining
 * observation is delivered and the adaptive state's watermark is advanced to {@link ReplayFixture#horizon()}, so the
 * final state is the state as of the export's examined horizon, not only of its last observation.
 *
 * <p>Synthetic outcomes ({@link OutcomeOracle.KnownForEveryRoute}) are generated for the replayed decision's route
 * and answer that decision. Exported outcomes ({@link OutcomeOracle.ChosenRouteOnly}) answer the logged decision and
 * are delivered whatever the replay advises, because they were really observed; a replayed route other than the
 * logged one is reported as a missing counterfactual and gets no invented outcome.
 *
 * <p>Data structures: tasks are already sorted by the fixture; pending observations arrive while the replay runs, so
 * they sit in a binary heap ({@code O(log P)} per insertion and removal). {@code P} is bounded by the fixture's
 * observation limit, {@value RebuildOrder#MAX_OBSERVATIONS}, not by its task count: a synthetic task adds at most two
 * observations when it executes, but a chosen-route-only fixture queues every logged revision before the first
 * decision.
 * Time {@code O(T (R log R + C) + P log P)} for {@code T} tasks, {@code R <= 32} routes and {@code C <= 256} cohorts,
 * plus {@code O(N log N + N L)} per rebuild after a correction. Retained: the state ({@code O(C + L)}), the delivered
 * observations ({@code O(P)}) and, when recording, one sample per task and one event per delivery.
 *
 * <p>Not thread-safe; a replay owns its state for its whole run and keeps nothing between runs.
 */
public final class TemporalReplay {

    private static final Comparator<Delivered> DELIVERY_ORDER = Comparator
            .comparingLong((Delivered d) -> d.observation().ledgerSequence())
            .thenComparing(d -> d.observation().experienceRef(), OpaqueTokens::compareCodePoints)
            .thenComparingLong(d -> d.observation().revision());

    private final ReplayFixture fixture;
    private final LexicographicRoutingPolicy policy;
    private final RoutingFeedbackPolicy feedbackPolicy;
    private final OutcomeEvaluator evaluator = new OutcomeEvaluator();
    private final ObservationFactory observations = new ObservationFactory();

    /** Creates a replay of a fixture. */
    public TemporalReplay(ReplayFixture fixture) {
        this.fixture = Objects.requireNonNull(fixture, "fixture must not be null");
        this.policy = fixture.policy();
        this.feedbackPolicy = fixture.definition().feedbackPolicy(fixture.evidencePolicy());
    }

    /** The fixture. */
    public ReplayFixture fixture() {
        return fixture;
    }

    /** Replays one arm and records every sample and feedback event. */
    public ReplayRun run(ReplayArm arm) {
        return run(arm, true);
    }

    /**
     * Replays one arm.
     *
     * @param arm the arm
     * @param record whether to build samples and feedback events; the measured windows skip them
     */
    ReplayRun run(ReplayArm arm, boolean record) {
        Objects.requireNonNull(arm, "arm must not be null");
        var run = new Run(arm, record);
        if (arm != ReplayArm.STATIC) {
            for (ReplayTask task : fixture.tasks()) {
                if (task.oracle() instanceof OutcomeOracle.ChosenRouteOnly logged) {
                    for (RoutingObservation observation : logged.observed()) {
                        run.pending.add(new Delivered(observation, logged.loggedDecision(), task.request(), false));
                    }
                }
            }
        }
        for (ReplayTask task : fixture.tasks()) {
            long cutoff = task.request().cutoff();
            while (!run.pending.isEmpty() && run.pending.peek().observation().ledgerSequence() <= cutoff) {
                run.deliver(run.pending.poll());
            }
            run.decide(task);
        }
        while (!run.pending.isEmpty()) {
            run.deliver(run.pending.poll());
        }
        if (arm == ReplayArm.ADAPTIVE && run.store.processedCutoff() < fixture.horizon()) {
            // the state has examined the export through its horizon, not only through its last observation
            run.store.advanceCutoff(fixture.horizon());
        }
        return new ReplayRun(arm, run.samples, run.events, run.delivered, run.trace.build(),
                Optional.ofNullable(run.store));
    }

    /**
     * Rebuilds the final adaptive state from scratch: a reset copy of the same cohorts replays every delivered
     * observation in rebuild order. Equality with the incrementally built state is the checkpoint-versus-scratch
     * equivalence the replay can show without a core checkpoint type.
     *
     * @param run an adaptive run
     * @return the rebuild result
     */
    public RebuildResult rebuildFromScratch(ReplayRun run) {
        RoutingStateStore state = run.state().orElseThrow(
                () -> new IllegalArgumentException("the " + run.arm() + " arm keeps no state"));
        Map<String, Delivered> answers = answers(run.delivered());
        List<RoutingObservation> delivered = run.delivered().stream().map(Delivered::observation).toList();
        RoutingStateStore scratch = state.resetCopy();
        return scratch.rebuild(delivered, state.processedCutoff(),
                observation -> derive(answers.get(observation.experienceRef()), observation, scratch));
    }

    private FeedbackDerivation derive(Delivered answer, RoutingObservation observation, RoutingStateStore store) {
        return feedbackPolicy.derive(observation, answer.decision(), answer.request(), store.bindings());
    }

    private static Map<String, Delivered> answers(List<Delivered> delivered) {
        var answers = new HashMap<String, Delivered>();
        for (Delivered d : delivered) {
            answers.putIfAbsent(d.observation().experienceRef(), d);
        }
        return answers;
    }

    private RoutingStateStore boundStore() {
        var store = new RoutingStateStore(fixture.definition(), fixture.partition());
        if (!(store.bind(fixture.cohortKeys()) instanceof BindResult.Bound)) {
            throw new IllegalStateException("the fixture's cohorts could not be bound");
        }
        return store;
    }

    /** Mutable state of one run; never escapes it. */
    private final class Run {

        private final ReplayArm arm;
        private final boolean record;
        private final PriorityQueue<Delivered> pending = new PriorityQueue<>(DELIVERY_ORDER);
        private final List<Delivered> delivered = new ArrayList<>();
        private final List<ReplaySample> samples = new ArrayList<>();
        private final List<FeedbackEvent> events = new ArrayList<>();
        private RoutingStateStore store;
        /** Every decision's route, kind, basis, abstention, coverage and snapshot watermark, recorded or not. */
        private final DecisionTrace.Builder trace = new DecisionTrace.Builder(fixture.tasks().size());
        private int decisions;

        Run(ReplayArm arm, boolean record) {
            this.arm = arm;
            this.record = record;
            this.store = arm == ReplayArm.STATIC ? null : boundStore();
        }

        void decide(ReplayTask task) {
            RoutingRequest request = task.request();
            RoutingPreference preference = store == null
                    ? RoutingPreference.empty(request, fixture.definition().state(), policy.policyId(),
                            policy.policyVersion())
                    : store.snapshot(policy, task.catalog());
            RoutingDecision decision = policy.decide(request, task.catalog(), preference);
            Optional<Outcome> outcome = Optional.empty();
            var coverage = ReplaySample.Coverage.NOT_EXECUTED;
            if (decision instanceof RoutingDecision.Selected selected) {
                switch (task.oracle()) {
                    case OutcomeOracle.KnownForEveryRoute known -> {
                        Optional<ScriptedOutcome> scripted = known.outcomeOf(selected.route());
                        coverage = scripted.isPresent() ? ReplaySample.Coverage.OBSERVED
                                : ReplaySample.Coverage.UNOBSERVED;
                        if (scripted.isPresent() && (arm != ReplayArm.STATIC || record)) {
                            outcome = Optional.of(execute(task, selected, scripted.get()));
                        }
                    }
                    case OutcomeOracle.ChosenRouteOnly logged -> {
                        if (!selected.route().equals(logged.loggedRoute())) {
                            coverage = ReplaySample.Coverage.MISSING_COUNTERFACTUAL;
                        } else if (logged.observed().isEmpty()) {
                            coverage = ReplaySample.Coverage.UNOBSERVED;
                        } else {
                            coverage = ReplaySample.Coverage.OBSERVED;
                            outcome = Optional.of(new Outcome(logged.observed().getLast(), logged.loggedDecision(),
                                    request, logged.chain()));
                        }
                    }
                }
            }
            trace.record(decisions++, decision, coverage, preference.processedCutoff());
            if (record) {
                samples.add(sample(task, decision, preference.processedCutoff(), coverage, outcome));
            }
        }

        /** Generates the replayed route's observations, queues them for delivery and returns the latest one. */
        private Outcome execute(ReplayTask task, RoutingDecision.Selected selected, ScriptedOutcome scripted) {
            RoutingRequest request = task.request();
            RoutingObservation original = observations.observe(request, selected, scripted, 0);
            RoutingObservation latest = original;
            int revision = 0;
            if (arm != ReplayArm.STATIC) {
                pending.add(new Delivered(original, selected, request, true));
            }
            if (scripted.correction().isPresent()) {
                latest = observations.observe(request, selected, scripted, 1);
                revision = 1;
                if (arm != ReplayArm.STATIC) {
                    pending.add(new Delivered(latest, selected, request, true));
                }
            }
            return new Outcome(latest, selected, request, observations.chainOf(scripted, revision));
        }

        void deliver(Delivered next) {
            RoutingObservation observation = next.observation();
            FeedbackDerivation derivation = derive(next, observation, store);
            delivered.add(next);
            FeedbackEvent.Update update = FeedbackEvent.Update.NOT_CONSUMED;
            if (arm == ReplayArm.ADAPTIVE) {
                update = switch (store.consume(observation, derivation)) {
                    case StateUpdate.Applied ignored -> FeedbackEvent.Update.APPLIED;
                    case StateUpdate.Recorded ignored -> FeedbackEvent.Update.RECORDED;
                    case StateUpdate.Unchanged ignored -> FeedbackEvent.Update.UNCHANGED;
                    case StateUpdate.Blocked ignored -> FeedbackEvent.Update.BLOCKED;
                    case StateUpdate.ForeignPartition ignored -> FeedbackEvent.Update.FOREIGN_PARTITION;
                    case StateUpdate.RebuildRequired ignored -> {
                        rebuild(observation);
                        yield FeedbackEvent.Update.REBUILT;
                    }
                };
            }
            if (record) {
                events.add(event(next, derivation, update));
            }
        }

        /** Stages a rebuild over everything delivered so far and swaps it in; a fixture that cannot is invalid. */
        private void rebuild(RoutingObservation trigger) {
            Map<String, Delivered> answers = answers(delivered);
            List<RoutingObservation> replayed = delivered.stream().map(Delivered::observation).toList();
            RoutingStateStore live = store;
            RebuildResult result = live.rebuild(replayed,
                    Math.max(live.processedCutoff(), trigger.ledgerSequence()),
                    observation -> derive(answers.get(observation.experienceRef()), observation, live));
            store = switch (result) {
                case RebuildResult.Rebuilt rebuilt -> rebuilt.store();
                case RebuildResult.Failed failed -> throw new IllegalStateException("rebuild after "
                        + trigger.experienceRef() + " stopped at " + failed.observation().experienceRef() + ": "
                        + failed.update());
            };
        }

        private FeedbackEvent event(Delivered next, FeedbackDerivation derivation, FeedbackEvent.Update update) {
            RoutingObservation observation = next.observation();
            LearningAssessment assessment = evaluator.assess(observation, next.decision(), next.request(),
                    fixture.evidencePolicy());
            return switch (derivation) {
                case FeedbackDerivation.Derived derived -> {
                    RoutingFeedback feedback = derived.feedback();
                    yield new FeedbackEvent(observation.experienceRef(), observation.revision(),
                            observation.ledgerSequence(), next.decision().provenance().decisionRef(),
                            next.request().stageKind(), observation.outcome().executedRoute(), next.replayed(),
                            assessment.verdict(), observation.outcome().attribution().basis(),
                            Optional.of(feedback.rule()), Optional.empty(),
                            feedback.entries().isEmpty() ? Optional.empty() : Optional.of(feedback.cohortKey()),
                            update);
                }
                case FeedbackDerivation.NoUpdate none -> new FeedbackEvent(observation.experienceRef(),
                        observation.revision(), observation.ledgerSequence(),
                        next.decision().provenance().decisionRef(), next.request().stageKind(),
                        observation.outcome().executedRoute(), next.replayed(), assessment.verdict(),
                        observation.outcome().attribution().basis(), Optional.empty(), Optional.of(none.reason()),
                        Optional.empty(), update);
            };
        }

        private ReplaySample sample(ReplayTask task, RoutingDecision decision, long stateCutoff,
                ReplaySample.Coverage coverage, Optional<Outcome> outcome) {
            RoutingRequest request = task.request();
            Optional<LearningAssessment> assessment = outcome.filter(o -> coverage == ReplaySample.Coverage.OBSERVED)
                    .map(o -> evaluator.assess(o.observation(), o.decision(), o.request(), fixture.evidencePolicy()));
            Optional<Outcome> observed = outcome.filter(o -> coverage == ReplaySample.Coverage.OBSERVED);
            var comparison = ComparisonKey.of(request, policy);
            return switch (decision) {
                case RoutingDecision.Selected selected -> new ReplaySample(DecisionRef.of(request),
                        request.stageKind(), request.cutoff(), stateCutoff, arm, ReplaySample.DecisionKind.SELECTED,
                        Optional.of(selected.route()), Optional.of(selected.basis()), Optional.empty(), coverage,
                        assessment.map(LearningAssessment::derivedAcceptance),
                        assessment.map(LearningAssessment::verdict),
                        assessment.map(LearningAssessment::provenFailures).orElse(List.of()),
                        observed.map(o -> o.chain().attempts()).orElse(0),
                        observed.map(o -> o.observation().outcome().resources().counters()).orElse(List.of()),
                        observed.map(o -> o.observation().outcome().resources().declaration()),
                        observed.map(o -> o.chain().cost()), comparison);
                case RoutingDecision.Abstain abstain -> notExecuted(request, stateCutoff,
                        ReplaySample.DecisionKind.ABSTAINED, Optional.of(abstain.reason()), comparison);
                case RoutingDecision.NoEligibleRoute ignored -> notExecuted(request, stateCutoff,
                        ReplaySample.DecisionKind.NO_ELIGIBLE_ROUTE, Optional.empty(), comparison);
            };
        }

        private ReplaySample notExecuted(RoutingRequest request, long stateCutoff, ReplaySample.DecisionKind kind,
                Optional<AbstainReason> reason, ComparisonKey comparison) {
            return new ReplaySample(DecisionRef.of(request), request.stageKind(), request.cutoff(), stateCutoff, arm,
                    kind,
                    Optional.empty(), Optional.empty(), reason, ReplaySample.Coverage.NOT_EXECUTED, Optional.empty(),
                    Optional.empty(), List.of(), 0, List.of(), Optional.empty(), Optional.empty(), comparison);
        }
    }

    /** The latest known revision of an executed route's outcome and the decision it answers. */
    private record Outcome(RoutingObservation observation, RoutingDecision.Selected decision, RoutingRequest request,
            ChainOutcome chain) {
    }
}
