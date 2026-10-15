package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.routing.ReplayRun.Delivered;
import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.adaptation.BindResult;
import monada.neuron.routing.adaptation.CompactionResult;
import monada.neuron.routing.adaptation.RebuildResult;
import monada.neuron.routing.adaptation.RoutingStateCheckpoint;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.adaptation.StateUpdate;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.feedback.RebuildOrder;
import monada.neuron.routing.feedback.RoutingFeedback;
import monada.neuron.routing.feedback.RoutingFeedbackPolicy;
import monada.neuron.routing.feedback.ScoreRule;
import monada.neuron.routing.outcome.LearningAssessment;
import monada.neuron.routing.outcome.OutcomeEvaluator;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.SortedSet;

/**
 * Chronological, leakage-free replay of one {@link ReplayFixture} under one {@link ReplayArm}.
 *
 * <p>Tasks are decided in {@link ReplayTask#ORDER}. The cutoff is inclusive (routing contract, "Ordinals and
 * cutoff"): before the decision at cutoff {@code c}, exactly the pending observations with {@code ledgerSequence <= c}
 * are delivered, in {@code (ledgerSequence, experienceRef, revision)} order, and an outcome, acceptance, correction or
 * usage observed after {@code c} cannot reach that decision. A decision's own outcome is visible strictly after its
 * cutoff ({@link ReplayTask}), so it is never delivered before the decision. The adaptive state then examines through
 * the cutoff itself ({@code processedCutoff = c}, contract "State must not be newer than the request": a replay
 * rebuilds the state as of each request's cutoff), so its snapshot is the state as of that cutoff whether or not an
 * observation sits exactly there. After the last decision every remaining observation is delivered and the watermark
 * is advanced to {@link ReplayFixture#horizon()}, so the final state is the state as of the export's examined horizon,
 * not only of its last observation.
 *
 * <p>Synthetic outcomes ({@link OutcomeOracle.KnownForEveryRoute}) are generated for the replayed decision's route
 * and answer that decision. An observation depends only on its task, the route and the decision's cohort binding,
 * catalog version and routing policy, so it is generated once per such key and reused by every later run, in every
 * arm: it stands for a ledger read, and the measured windows time the replay, not the harness's hashing. Exported outcomes ({@link OutcomeOracle.ChosenRouteOnly}) answer the logged decision and
 * are delivered whatever the replay advises, because they were really observed; a replayed route other than the
 * logged one is reported as a missing counterfactual and gets no invented outcome.
 *
 * <p>Data structures: tasks are already sorted by the fixture; pending observations arrive while the replay runs, so
 * they sit in a binary heap ({@code O(log P)} per insertion and removal). {@code P} is bounded by the fixture's
 * observation limit, {@value RebuildOrder#MAX_OBSERVATIONS}, not by its task count: a synthetic task adds at most two
 * observations when it executes, but a chosen-route-only fixture queues every logged revision before the first
 * decision.
 * Time {@code O(T (R log R + C) + P log P)} for {@code T} tasks, {@code R <= 32} routes and {@code C <= 256} cohorts,
 * plus {@code O(N log N + N L)} per rebuild after a correction, which reuses each observation's derivation from its
 * delivery (the bindings never change during a run). {@code N} is every delivered observation from scratch, or only
 * those after the starting checkpoint with {@link RebuildStrategy#CHECKPOINT}. Retained: the state
 * ({@code O(C + L)}), the delivered observations and their derivations ({@code O(P)}), at most
 * {@link RebuildStrategy#RETAINED_CHECKPOINTS} checkpoints ({@code O(C)} each) and, when recording, one sample per task
 * and one event per delivery.
 *
 * <p>Not thread-safe. A run owns its state for its whole run; between runs the replay keeps only the generated
 * synthetic observations, a pure function of the fixture.
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
    private final SortedSet<CohortKey> cohortKeys;
    /** Generated synthetic observations by task and decision provenance; filled by the first run that needs one. */
    private final Map<GenerationKey, Generated> generated = new HashMap<>();

    /** Creates a replay of a fixture. */
    public TemporalReplay(ReplayFixture fixture) {
        this.fixture = Objects.requireNonNull(fixture, "fixture must not be null");
        this.policy = fixture.policy();
        this.feedbackPolicy = fixture.definition().feedbackPolicy(fixture.evidencePolicy());
        this.cohortKeys = fixture.cohortKeys();
    }

    /** Replays one arm, rebuilding from scratch, and records every sample and feedback event. */
    public ReplayRun run(ReplayArm arm) {
        return run(arm, true, RebuildStrategy.SCRATCH);
    }

    /** Replays one arm with the given rebuild strategy and records every sample and feedback event. */
    public ReplayRun run(ReplayArm arm, RebuildStrategy strategy) {
        return run(arm, true, strategy);
    }

    /** {@link #run(ReplayArm, boolean, RebuildStrategy)} rebuilding from scratch. */
    ReplayRun run(ReplayArm arm, boolean record) {
        return run(arm, record, RebuildStrategy.SCRATCH);
    }

    /**
     * Replays one arm.
     *
     * @param arm the arm
     * @param record whether to build samples and feedback events; the measured windows skip them
     * @param strategy where correction-triggered rebuilds start; only the adaptive arm rebuilds
     */
    ReplayRun run(ReplayArm arm, boolean record, RebuildStrategy strategy) {
        Objects.requireNonNull(arm, "arm must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        var run = new Run(arm, record, strategy);
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
            if (arm == ReplayArm.ADAPTIVE && run.store.processedCutoff() < cutoff) {
                // everything at or before the cutoff has been delivered: the state is the state as of the cutoff
                run.store.advanceCutoff(cutoff);
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
                run.tally.build(), Optional.ofNullable(run.store),
                new ReplayRun.RebuildStats(strategy, run.rebuilds, run.fromCheckpoint, run.replayed,
                        run.checkpointsTaken, run.compactions, run.compactedEntries));
    }

    /**
     * Rebuilds the final adaptive state from scratch: a reset copy of the same cohorts replays every delivered
     * observation in rebuild order. Its equality with the incrementally built state is the equivalence a checkpoint
     * must preserve. Every observation is derived again, so a wrong reuse of a delivery-time derivation in the
     * incremental run shows up as a difference.
     *
     * @param run an adaptive run
     * @return the rebuild result
     * @throws IllegalArgumentException if the run is not adaptive: only the adaptive arm consumes what it derives
     */
    public RebuildResult rebuildFromScratch(ReplayRun run) {
        RoutingStateStore state = adaptiveState(run);
        Map<String, Delivered> answers = answers(run.delivered());
        List<RoutingObservation> delivered = run.delivered().stream().map(Delivered::observation).toList();
        RoutingStateStore scratch = state.resetCopy();
        return scratch.rebuild(delivered, state.processedCutoff(),
                observation -> derive(answers.get(observation.experienceRef()), observation, scratch));
    }

    /**
     * Rebuilds the final adaptive state from the checkpoint its ledger was compacted to, replaying only the delivered
     * observations after that checkpoint, each derived again against the staged bindings.
     *
     * @param run an adaptive run with the {@link RebuildStrategy#CHECKPOINT} strategy
     * @return the rebuild result
     * @throws IllegalArgumentException if the run is not adaptive or its state was never compacted
     */
    public RebuildResult rebuildFromCheckpoint(ReplayRun run) {
        RoutingStateStore state = adaptiveState(run);
        RoutingStateCheckpoint checkpoint = state.latestCheckpoint().orElseThrow(
                () -> new IllegalArgumentException("the adaptive state was never compacted"));
        Map<String, Delivered> answers = answers(run.delivered());
        return state.rebuildFrom(checkpoint, after(run.delivered(), checkpoint), state.processedCutoff(),
                (observation, bindings) -> {
                    Delivered answer = answers.get(observation.experienceRef());
                    return feedbackPolicy.derive(observation, answer.decision(), answer.request(), bindings);
                });
    }

    private static RoutingStateStore adaptiveState(ReplayRun run) {
        if (run.arm() != ReplayArm.ADAPTIVE) {
            throw new IllegalArgumentException("only an adaptive run consumes its observations; the " + run.arm()
                    + " arm's state is not built from them");
        }
        return run.state().orElseThrow();
    }

    /** The delivered observations after a checkpoint's cutoff, in delivery order. */
    private static List<RoutingObservation> after(List<Delivered> delivered, RoutingStateCheckpoint checkpoint) {
        var after = new ArrayList<RoutingObservation>();
        for (Delivered d : delivered) {
            if (d.observation().ledgerSequence() > checkpoint.processedCutoff()) {
                after.add(d.observation());
            }
        }
        return after;
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
        if (!(store.bind(cohortKeys) instanceof BindResult.Bound)) {
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
        /** Each delivered observation's derivation, by identity: a rebuild reuses it instead of deriving again. */
        private final Map<RoutingObservation, FeedbackDerivation> derivations = new IdentityHashMap<>();
        /** Derivations by rule and reason, counted whether or not events are recorded. */
        /** Sized for a synthetic fixture's most deliveries (two per task); grows for a longer logged history. */
        private final Derivations.Tally tally = new Derivations.Tally(2 * fixture.tasks().size());
        private RoutingStateStore store;
        /** Every decision's route, kind, basis, abstention, coverage and snapshot watermark, recorded or not. */
        private final DecisionTrace.Builder trace = new DecisionTrace.Builder(fixture.tasks().size());
        private int decisions;
        private final RebuildStrategy strategy;
        /** Checkpoints still valid for a rebuild, oldest first; at most {@link RebuildStrategy#RETAINED_CHECKPOINTS}. */
        private final ArrayDeque<RoutingStateCheckpoint> checkpoints = new ArrayDeque<>();
        private int rebuilds;
        private int fromCheckpoint;
        private long replayed;
        private int checkpointsTaken;
        private int compactions;
        private int compactedEntries;

        Run(ReplayArm arm, boolean record, RebuildStrategy strategy) {
            this.arm = arm;
            this.record = record;
            this.strategy = strategy;
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
                        if (scripted.isPresent()) {
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
            trace.record(decisions++, decision, coverage, preference.processedCutoff(), delivered.size());
            if (record) {
                samples.add(sample(task, decision, preference.processedCutoff(), coverage, outcome));
            }
        }

        /**
         * The replayed route's observations, generated once per task and decision provenance; queues them for
         * delivery (unless the arm keeps no state) and returns the latest one.
         */
        private Outcome execute(ReplayTask task, RoutingDecision.Selected selected, ScriptedOutcome scripted) {
            RoutingRequest request = task.request();
            var key = new GenerationKey(decisions, selected.route(), selected.cohortBinding(),
                    selected.provenance().catalogVersion(), selected.provenance().policyId(),
                    selected.provenance().policyVersion());
            Generated observed = generated.computeIfAbsent(key, ignored -> new Generated(
                    observations.observe(request, selected, scripted, 0),
                    scripted.correction().map(c -> observations.observe(request, selected, scripted, 1)),
                    observations.chainOf(scripted, scripted.correction().isPresent() ? 1 : 0)));
            if (arm != ReplayArm.STATIC) {
                pending.add(new Delivered(observed.original(), selected, request, true));
                observed.correction().ifPresent(c -> pending.add(new Delivered(c, selected, request, true)));
            }
            return new Outcome(observed.correction().orElse(observed.original()), selected, request,
                    observed.chain());
        }

        void deliver(Delivered next) {
            RoutingObservation observation = next.observation();
            FeedbackDerivation derivation = derive(next, observation, store);
            derivations.put(observation, derivation);
            tally.count(derivation);
            delivered.add(next);
            FeedbackEvent.Update update = FeedbackEvent.Update.NOT_CONSUMED;
            if (arm == ReplayArm.ADAPTIVE) {
                // contract section 7: a checkpoint is valid only below the earliest revision a correction supersedes
                if (observation.supersedesSequence() instanceof Feature.Known<Long> known) {
                    checkpoints.removeIf(c -> c.processedCutoff() >= known.value());
                }
                update = switch (store.consume(observation, derivation)) {
                    case StateUpdate.Applied ignored -> FeedbackEvent.Update.APPLIED;
                    case StateUpdate.Recorded ignored -> FeedbackEvent.Update.RECORDED;
                    case StateUpdate.Unchanged ignored -> FeedbackEvent.Update.UNCHANGED;
                    case StateUpdate.Blocked ignored -> FeedbackEvent.Update.BLOCKED;
                    case StateUpdate.ForeignPartition ignored -> FeedbackEvent.Update.FOREIGN_PARTITION;
                    case StateUpdate.RebuildRequired rebuild -> {
                        rebuild(observation, rebuild.advice().affectedFromSequence());
                        yield FeedbackEvent.Update.REBUILT;
                    }
                    case StateUpdate.BeyondCheckpoint beyond -> {
                        if (beyond.kind() == StateUpdate.BeyondCheckpoint.Kind.COVERED) {
                            yield FeedbackEvent.Update.UNCHANGED;
                        }
                        rebuild(observation, beyond.affectedFromSequence());
                        yield FeedbackEvent.Update.REBUILT;
                    }
                };
                if (strategy == RebuildStrategy.CHECKPOINT
                        && delivered.size() % RebuildStrategy.CHECKPOINT_INTERVAL == 0) {
                    checkpoint();
                }
            }
            if (record) {
                events.add(event(next, derivation, update));
            }
        }

        /**
         * Stages a rebuild and swaps it in; a fixture that cannot is invalid. With checkpoints it starts from the
         * newest one strictly below {@code affectedFrom} and replays only the observations after it; otherwise it
         * replays everything delivered so far. Each observation's derivation is the one made at its delivery: the
         * bindings it read never change during a run (a checkpoint holds exactly the run's cohorts).
         */
        private void rebuild(RoutingObservation trigger, long affectedFrom) {
            rebuilds++;
            checkpoints.removeIf(c -> c.processedCutoff() >= affectedFrom);
            RoutingStateStore live = store;
            long exportCutoff = Math.max(live.processedCutoff(), trigger.ledgerSequence());
            RebuildResult result;
            if (!checkpoints.isEmpty()) {
                RoutingStateCheckpoint start = checkpoints.getLast();
                List<RoutingObservation> later = after(delivered, start);
                fromCheckpoint++;
                replayed += later.size();
                result = live.rebuildFrom(start, later, exportCutoff, (observation, bindings) -> derivationOf(observation));
            } else {
                List<RoutingObservation> all = delivered.stream().map(Delivered::observation).toList();
                replayed += all.size();
                result = live.rebuild(all, exportCutoff, this::derivationOf);
            }
            store = switch (result) {
                case RebuildResult.Rebuilt rebuilt -> rebuilt.store();
                case RebuildResult.Failed failed -> throw new IllegalStateException("rebuild after "
                        + trigger.experienceRef() + " stopped at " + failed.observation().experienceRef() + ": "
                        + failed.update());
            };
        }

        private FeedbackDerivation derivationOf(RoutingObservation observation) {
            FeedbackDerivation derivation = derivations.get(observation);
            if (derivation == null) {
                throw new IllegalStateException("no derivation was made for " + observation.experienceRef()
                        + " revision " + observation.revision());
            }
            return derivation;
        }

        /** Takes a checkpoint, keeps the newest few and compacts the ledger to the oldest kept one. */
        private void checkpoint() {
            checkpoints.addLast(store.checkpoint(policy));
            checkpointsTaken++;
            while (checkpoints.size() > RebuildStrategy.RETAINED_CHECKPOINTS) {
                checkpoints.removeFirst();
            }
            switch (store.compact(checkpoints.getFirst())) {
                case CompactionResult.Compacted done -> {
                    if (done.removed() > 0) {
                        compactions++;
                        compactedEntries += done.removed();
                    }
                }
                // a store restored from a newer checkpoint is already compacted past the oldest one
                case CompactionResult.Refused refused
                        when refused.reason() == CompactionResult.Reason.BEHIND_HORIZON -> {
                }
                case CompactionResult.Refused refused -> throw new IllegalStateException(
                        "a valid checkpoint was refused: " + refused.reason());
            }
        }

        private FeedbackEvent event(Delivered next, FeedbackDerivation derivation, FeedbackEvent.Update update) {
            RoutingObservation observation = next.observation();
            LearningAssessment assessment = evaluator.assess(observation, next.decision(), next.request(),
                    fixture.evidencePolicy());
            RawEvidence raw = RawEvidence.of(observation.outcome(), fixture.evidencePolicy());
            Optional<ScoreRule> rule = Optional.empty();
            Optional<NoUpdateReason> noUpdate = Optional.empty();
            Optional<CohortKey> credited = Optional.empty();
            switch (derivation) {
                case FeedbackDerivation.Derived derived -> {
                    RoutingFeedback feedback = derived.feedback();
                    rule = Optional.of(feedback.rule());
                    credited = feedback.entries().isEmpty() ? Optional.empty() : Optional.of(feedback.cohortKey());
                }
                case FeedbackDerivation.NoUpdate none -> noUpdate = Optional.of(none.reason());
            }
            return new FeedbackEvent(observation.experienceRef(), observation.revision(),
                    observation.ledgerSequence(), next.decision().provenance().decisionRef(),
                    next.request().stageKind(), observation.outcome().executedRoute(),
                    next.decision().cohortBinding().cohortBucket(), next.replayed(), assessment.verdict(),
                    observation.outcome().attribution().basis(), raw.reward(), raw.penalty(), rule, noUpdate,
                    credited, update);
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

    /** What identifies a generated synthetic observation: the task index and every decision field it reads. */
    private record GenerationKey(int task, RouteKey route, CohortBinding binding, String catalogVersion,
            String policyId, String policyVersion) {
    }

    /** A generated original, its correction if scripted, and the latest revision's chain. */
    private record Generated(RoutingObservation original, Optional<RoutingObservation> correction,
            ChainOutcome chain) {
    }

    /** The latest known revision of an executed route's outcome and the decision it answers. */
    private record Outcome(RoutingObservation observation, RoutingDecision.Selected decision, RoutingRequest request,
            ChainOutcome chain) {
    }
}
