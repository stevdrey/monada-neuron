package monada.neuron.evaluation.routing;

import monada.neuron.evaluation.routing.RoutingReplayEvaluation.Check;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.RoutingInput;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.TrustedRoutingProfile;
import monada.neuron.routing.adaptation.BindResult;
import monada.neuron.routing.adaptation.RebuildResult;
import monada.neuron.routing.adaptation.RoutingStateCheckpoint;
import monada.neuron.routing.adaptation.StatePartition;
import monada.neuron.routing.adaptation.codec.CheckpointCodec;
import monada.neuron.routing.adaptation.codec.CheckpointDecodeResult;
import monada.neuron.routing.adaptation.recovery.BlockerKind;
import monada.neuron.routing.adaptation.recovery.CheckpointRecovery;
import monada.neuron.routing.adaptation.recovery.ExportState;
import monada.neuron.routing.adaptation.recovery.RecoveryAction;
import monada.neuron.routing.adaptation.recovery.RecoveryAssessment;
import monada.neuron.routing.adaptation.recovery.RecoveryBlocker;
import monada.neuron.routing.adaptation.recovery.RecoveryPlan;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.feedback.TargetBindings;
import monada.neuron.routing.adaptation.RoutingStateStore;
import monada.neuron.routing.adaptation.StateUpdate;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.comparison.EligibilityChange;
import monada.neuron.routing.comparison.RouteStanding;
import monada.neuron.routing.comparison.RoutingScenario;
import monada.neuron.routing.comparison.ScenarioComparator;
import monada.neuron.routing.comparison.ScenarioComparison;
import monada.neuron.routing.comparison.ScenarioDifference;
import monada.neuron.routing.disposition.AdviceDisposition;
import monada.neuron.routing.disposition.DispositionResult;
import monada.neuron.routing.feedback.FeedbackDerivation;
import monada.neuron.routing.feedback.NoUpdateReason;
import monada.neuron.routing.outcome.ProvenanceField;
import monada.neuron.routing.outcome.RoutingObservation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The Forge host scenario suite (issue #103): Forge like task sequences replayed through {@link TemporalReplay} with a
 * scripted {@link HostScript} between each decision and its execution, over the static and adaptive arms.
 *
 * <p>It checks that the integration seams hold together: a human disposition alone earns no credit, an override
 * credits nothing under v1, advice, receipts and recovery stay deterministic, and quality and cost are reported over
 * observed samples only. It proves contract consistency over synthetic data, not routing quality: no figure is an
 * effectiveness, causal or billing claim. Nothing is timed, and every digest excludes the clock and the environment.
 *
 * <p>Each run gets its own replay and script, because a script records what it saw.
 */
final class HostScenarioEvaluation {

    /** The two arms a host meets: the rule baseline and the learning state. */
    static final List<ReplayArm> ARMS = List.of(ReplayArm.STATIC, ReplayArm.ADAPTIVE);

    /** Complete evaluation output; there are no timed results. */
    record Outcome(List<Check> checks, Map<String, String> metadata) {

        /** Copies. */
        Outcome {
            checks = List.copyOf(checks);
            metadata = Map.copyOf(metadata);
        }

        /** Whether every check passed. */
        boolean allPassed() {
            return checks.stream().allMatch(Check::passed);
        }
    }

    /**
     * One scenario's runs and host records per arm.
     *
     * @param name the scenario name
     * @param fixture its fixture
     * @param runs the run of each arm in {@link #ARMS}
     * @param records the host's records of each arm, one per decision in replay order
     */
    record HostRun(String name, ReplayFixture fixture, Map<ReplayArm, ReplayRun> runs,
            Map<ReplayArm, List<HostDecisionRecord>> records) {
    }

    private final HostScenarioFixtures fixtures = new HostScenarioFixtures();
    private final ReplayDigests digests = new ReplayDigests();
    private final ObservationFactory observations = new ObservationFactory();
    private final HostDigests hostDigests = new HostDigests();

    /** Runs every scenario and check. */
    Outcome run() {
        Evaluated first = evaluate();
        Evaluated second = new HostScenarioEvaluation().evaluate();
        var checks = new ArrayList<>(first.checks());
        checks.add(deterministicCheck(first.checks(), first.metadata(), second.checks(), second.metadata()));
        return new Outcome(checks, first.metadata());
    }

    /** One complete pass: its checks and every metadata line, digests included. */
    private record Evaluated(List<Check> checks, Map<String, String> metadata) {
    }

    /** Runs every scenario, probe and check once, from fresh fixtures. */
    private Evaluated evaluate() {
        var metadata = new TreeMap<String, String>();
        metadata.put("host.fixtureVersion", HostScenarioFixtures.VERSION);
        var checks = new ArrayList<Check>();

        HostRun coldStart = run("cold-start", fixtures.coldStart(), Map.of());
        HostRun missingFeatures = run("missing-features", fixtures.missingFeatures(), Map.of());
        HostRun catalogChange = run("catalog-change", fixtures.catalogChange(), Map.of());
        HostRun disposition = run("disposition", fixtures.disposition(), fixtures.dispositionScript());
        HostRun delayed = run("delayed-acceptance", fixtures.delayedAcceptance(), Map.of());
        HostRun restart = run("restart", fixtures.restart(), Map.of());
        List<HostRun> scenarios = List.of(coldStart, missingFeatures, catalogChange, disposition, delayed, restart);
        ReplayRun checkpointed = new TemporalReplay(restart.fixture(), new HostScript(restart.fixture(), Map.of(),
                HostScenarioFixtures.REQUIRED_FOR_PREVIEW)).run(ReplayArm.ADAPTIVE, RebuildStrategy.CHECKPOINT);
        List<RecoveryProbe> probes = restartProbes(restart);

        checks.add(noCreditPathCheck(disposition));
        checks.add(executedRouteOnlyCheck(disposition, runWithoutAlternatives(disposition)));
        checks.add(catalogChangeCheck(catalogChange, metadata));
        checks.add(observedOnlyCheck(scenarios));
        checks.add(futureLeakageCheck(List.of(leakageVariants(delayed), leakageVariants(restart))));
        checks.add(recoveryParityCheck(restart, checkpointed, probes));
        checks.add(unrecoverableReportedCheck(probes));
        checks.add(staticWiringCheck(scenarios));
        checks.add(RoutingReplayEvaluation.eligibilityCheck(scenarios.stream().map(HostRun::runs)
                .<Map<ReplayArm, ReplayRun>>map(Map::copyOf).toList()));
        checks.add(boundsCheck(scenarios));

        for (HostRun scenario : scenarios) {
            describe(scenario, metadata);
            fingerprint(scenario, metadata);
        }
        // after every digest: it consumes the foreign observations into the adaptive run's final state, so whatever a
        // consume leaves behind shows in this check alone, never in a scenario digest
        checks.add(foreignEvidenceCheck(delayed, metadata));
        for (RecoveryProbe probe : probes) {
            String prefix = "host.restart.probe." + probe.caseId() + ".";
            metadata.put(prefix + "outcome", probe.outcome().name());
            metadata.put(prefix + "blockers", blockerKinds(probe.assessment()).toString());
            metadata.put(prefix + "plan", probe.plan().steps().stream().map(step -> step.action().name()).toList()
                    .toString());
            metadata.put(prefix + "checkpointBytes", Integer.toString(probe.checkpointBytes()));
            metadata.put(prefix + "parity", Boolean.toString(probe.parity()));
        }
        metadata.put("host.digest.recovery", hostDigests.recovery(probes));
        return new Evaluated(checks, metadata);
    }

    /** The blocked restart cases: how each is built and the exact blockers and first plan action it must report. */
    private record Blocked(String caseId, Set<BlockerKind> blockers, RecoveryAction firstAction) {
    }

    // a list in case order, so a failing check reports its cases in a fixed order
    static final List<Blocked> BLOCKED_CASES = List.of(
            new Blocked("corrupt-bytes", EnumSet.of(BlockerKind.UNDECODABLE),
                    RecoveryAction.OBTAIN_READABLE_CHECKPOINT),
            new Blocked("foreign-scope", EnumSet.of(BlockerKind.FOREIGN_SCOPE), RecoveryAction.FIX_HOST_CONFIGURATION),
            new Blocked("foreign-policy", EnumSet.of(BlockerKind.FOREIGN_EVALUATION_POLICY),
                    RecoveryAction.FIX_HOST_CONFIGURATION),
            new Blocked("export-behind", EnumSet.of(BlockerKind.EXPORT_BEHIND_CHECKPOINT), RecoveryAction.OBTAIN_EXPORT));

    /**
     * The restart probe, run after the scenario's adaptive run. Acting as a host, it rebuilds a fresh store from the
     * observations it had delivered by task {@value HostScenarioFixtures#CHECKPOINT_TASK}'s cutoff, persists that
     * state as a {@code routing-checkpoint/1} checkpoint, and restores it into fresh target stores: once with only the
     * correction that supersedes an outcome after the checkpoint, once with both corrections, and once for each blocked
     * case. A compatible case calls {@code rebuildFrom} over the rest of the export; a blocked case never does.
     */
    List<RecoveryProbe> restartProbes(HostRun scenario) {
        ReplayFixture fixture = scenario.fixture();
        ReplayRun run = scenario.runs().get(ReplayArm.ADAPTIVE);
        var policy = fixture.definition().feedbackPolicy(fixture.evidencePolicy());
        var answers = new HashMap<String, ReplayRun.Delivered>();
        run.delivered().forEach(d -> answers.putIfAbsent(d.observation().experienceRef(), d));
        BiFunction<RoutingObservation, TargetBindings, FeedbackDerivation> derive = (observation, bindings) -> {
            ReplayRun.Delivered answer = answers.get(observation.experienceRef());
            return policy.derive(observation, answer.decision(), answer.request(), bindings);
        };
        List<RoutingObservation> all = run.delivered().stream().map(ReplayRun.Delivered::observation).toList();
        long checkpointCutoff = fixtures.cutoff(HostScenarioFixtures.CHECKPOINT_TASK);
        long horizon = fixture.horizon();

        RoutingStateStore atCheckpoint = scratch(fixture, all.stream()
                .filter(o -> o.ledgerSequence() <= checkpointCutoff).toList(), checkpointCutoff, derive);
        byte[] bytes = CheckpointCodec.encode(atCheckpoint.checkpoint(fixture.policy()));

        String preRef = observations.experienceRef(
                fixture.tasks().get(HostScenarioFixtures.PRE_CHECKPOINT_CORRECTED_TASK).request());
        List<RoutingObservation> postOnly = all.stream()
                .filter(o -> !(o.experienceRef().equals(preRef) && o.revision() == 1)).toList();
        String postReference = digests.state(scratch(fixture, postOnly, horizon, derive));
        String fullReference = digests.state(run.state().orElseThrow());

        var probes = new ArrayList<RecoveryProbe>();
        probes.add(restore("post-checkpoint-correction", bytes, bound(fixture, fixture.partition(), SCOPE_UNCHANGED),
                new ExportState.Observed(horizon), postOnly, postReference, fixture, derive));
        probes.add(restore("pre-checkpoint-correction", bytes, bound(fixture, fixture.partition(), SCOPE_UNCHANGED),
                new ExportState.Observed(horizon), all, fullReference, fixture, derive));
        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length / 2] ^= (byte) 0xFF;
        probes.add(restore("corrupt-bytes", corrupt, bound(fixture, fixture.partition(), SCOPE_UNCHANGED),
                new ExportState.Observed(horizon), all, fullReference, fixture, derive));
        StatePartition partition = fixture.partition();
        probes.add(restore("foreign-scope", bytes, bound(fixture, new StatePartition(HostScenarioFixtures.FOREIGN_SCOPE,
                partition.evaluationPolicyId(), partition.evaluationPolicyVersion()), HostScenarioFixtures.FOREIGN_SCOPE),
                new ExportState.Observed(horizon), all, fullReference, fixture, derive));
        probes.add(restore("foreign-policy", bytes, bound(fixture, new StatePartition(partition.scopeId(),
                partition.evaluationPolicyId(), HostScenarioFixtures.FOREIGN_EVALUATION_VERSION), SCOPE_UNCHANGED),
                new ExportState.Observed(horizon), all, fullReference, fixture, derive));
        probes.add(restore("export-behind", bytes, bound(fixture, fixture.partition(), SCOPE_UNCHANGED),
                new ExportState.Observed(checkpointCutoff - 1), all, fullReference, fixture, derive));
        return probes;
    }

    /** Marks a target store bound to the fixture's own cohort scope. */
    private static final String SCOPE_UNCHANGED = "";

    /**
     * Decodes, assesses and, when compatible, rebuilds from the checkpoint; a rebuild that stops falls back to a
     * rebuild from scratch, as {@code RecoveryPlan.after} tells a host to.
     */
    private RecoveryProbe restore(String caseId, byte[] bytes, RoutingStateStore target, ExportState export,
            List<RoutingObservation> delivered, String reference, ReplayFixture fixture,
            BiFunction<RoutingObservation, TargetBindings, FeedbackDerivation> derive) {
        CheckpointDecodeResult decoded = CheckpointCodec.decode(bytes);
        RecoveryAssessment assessment = CheckpointRecovery.assess(decoded, target, export);
        Optional<String> sha = Optional.of(hostDigests.sha256(bytes));
        return switch (assessment) {
            case RecoveryAssessment.Blocked ignored -> new RecoveryProbe(caseId, assessment, Optional.empty(),
                    RecoveryPlan.of(assessment), sha, bytes.length, RecoveryProbe.RecoveryOutcome.BLOCKED, false);
            case RecoveryAssessment.Compatible ignored -> {
                RoutingStateCheckpoint checkpoint = ((CheckpointDecodeResult.Decoded) decoded).checkpoint();
                long exportCutoff = ((ExportState.Observed) export).highWatermark();
                RebuildResult result = target.rebuildFrom(checkpoint, delivered, exportCutoff, derive);
                yield switch (result) {
                    case RebuildResult.Rebuilt rebuilt -> new RecoveryProbe(caseId, assessment, Optional.of(result),
                            RecoveryPlan.of(assessment), sha, bytes.length,
                            RecoveryProbe.RecoveryOutcome.COMPATIBLE_REBUILT,
                            digests.state(rebuilt.store()).equals(reference));
                    case RebuildResult.Failed failed -> new RecoveryProbe(caseId, assessment, Optional.of(result),
                            RecoveryPlan.after(failed), sha, bytes.length,
                            RecoveryProbe.RecoveryOutcome.REBUILD_FAILED_SCRATCH_FALLBACK,
                            digests.state(scratch(fixture, delivered, exportCutoff, derive)).equals(reference));
                };
            }
        };
    }

    /** A fresh store of the fixture's definition bound to its cohorts, rebuilt from scratch over the observations. */
    private RoutingStateStore scratch(ReplayFixture fixture, List<RoutingObservation> delivered, long exportCutoff,
            BiFunction<RoutingObservation, TargetBindings, FeedbackDerivation> derive) {
        RoutingStateStore store = bound(fixture, fixture.partition(), SCOPE_UNCHANGED);
        return switch (store.rebuild(delivered, exportCutoff, observation -> derive.apply(observation,
                store.bindings()))) {
            case RebuildResult.Rebuilt rebuilt -> rebuilt.store();
            case RebuildResult.Failed failed -> throw new IllegalStateException("a rebuild from scratch stopped at "
                    + failed.observation().experienceRef() + ": " + failed.update());
        };
    }

    /** A fresh store of the partition bound to the fixture's cohorts, re-scoped when {@code scope} is not empty. */
    private RoutingStateStore bound(ReplayFixture fixture, StatePartition partition, String scope) {
        var store = new RoutingStateStore(fixture.definition(), partition);
        List<CohortKey> keys = fixture.cohortKeys().stream().map(key -> scope.isEmpty() ? key
                : new CohortKey(scope, key.stageKind(), key.cohortBucket(), key.route())).toList();
        if (!(store.bind(keys) instanceof BindResult.Bound)) {
            throw new IllegalStateException("the restart target's cohorts could not be bound");
        }
        return store;
    }

    /**
     * A restart matches the from scratch reference or reports that it cannot. A correction after the checkpoint
     * rebuilds from it to the same state digest as a rebuild from scratch over the same export; one at or before it
     * stops {@code rebuildFrom} with {@code SUPERSEDES_COMPACTED}, its plan is recorded, and the scratch fallback
     * matches the run's final state; the checkpointed and scratch runs decide identically. Vacuous unless the
     * checkpoint went through an encode and decode round trip.
     */
    Check recoveryParityCheck(HostRun scenario, ReplayRun checkpointed, List<RecoveryProbe> probes) {
        var failures = new ArrayList<String>();
        RecoveryProbe post = probe(probes, "post-checkpoint-correction");
        RecoveryProbe pre = probe(probes, "pre-checkpoint-correction");
        if (post.outcome() != RecoveryProbe.RecoveryOutcome.COMPATIBLE_REBUILT || !post.parity()) {
            failures.add("post-checkpoint-correction ended " + post.outcome() + " with parity " + post.parity());
        }
        boolean compacted = pre.rebuild().orElse(null) instanceof RebuildResult.Failed failed
                && failed.update() instanceof StateUpdate.BeyondCheckpoint beyond
                && beyond.kind() == StateUpdate.BeyondCheckpoint.Kind.SUPERSEDES_COMPACTED;
        if (pre.outcome() != RecoveryProbe.RecoveryOutcome.REBUILD_FAILED_SCRATCH_FALLBACK || !compacted
                || pre.plan().steps().isEmpty() || !pre.parity()) {
            failures.add("pre-checkpoint-correction ended " + pre.outcome() + " (" + pre.rebuild() + ") with parity "
                    + pre.parity());
        }
        ReplayRun scratch = scenario.runs().get(ReplayArm.ADAPTIVE);
        if (!digests.decisions(scratch).equals(digests.decisions(checkpointed))
                || !digests.state(scratch.state().orElseThrow()).equals(digests.state(checkpointed.state()
                        .orElseThrow()))) {
            failures.add("the checkpointed and scratch runs of " + scenario.name() + " differ");
        }
        boolean roundTrip = post.assessment() instanceof RecoveryAssessment.Compatible && post.checkpointBytes() > 0;
        if (!roundTrip) {
            failures.add("no checkpoint went through an encode and decode round trip");
        }
        boolean passed = failures.isEmpty();
        return new Check("host.recovery.parity", passed, passed
                ? "a " + post.checkpointBytes() + " byte routing-checkpoint/1 restored with a later correction rebuilt"
                        + " to the scratch state digest; with an earlier correction rebuildFrom stopped at"
                        + " SUPERSEDES_COMPACTED, the plan " + pre.plan().steps().stream().map(s -> s.action().name())
                        .toList() + " was recorded and the scratch fallback matched; checkpointed and scratch runs"
                        + " decided identically"
                : String.join("; ", failures));
    }

    /**
     * Each blocked restart case reports exactly its expected blocker set and first plan action, and none reached
     * {@code rebuildFrom}. Vacuous unless every blocked case ran.
     */
    Check unrecoverableReportedCheck(List<RecoveryProbe> probes) {
        var failures = new ArrayList<String>();
        for (Blocked expected : BLOCKED_CASES) {
            String caseId = expected.caseId();
            RecoveryProbe probe = probe(probes, caseId);
            Set<BlockerKind> actual = blockerKinds(probe.assessment());
            RecoveryAction first = probe.plan().steps().isEmpty() ? null : probe.plan().steps().getFirst().action();
            if (probe.outcome() != RecoveryProbe.RecoveryOutcome.BLOCKED || probe.rebuild().isPresent()
                    || !actual.equals(expected.blockers()) || first != expected.firstAction()) {
                failures.add(caseId + " reported " + actual + " then " + first + " (" + probe.outcome()
                        + "), expected " + expected.blockers() + " then " + expected.firstAction());
            }
        }
        boolean passed = failures.isEmpty();
        return new Check("host.recovery.unrecoverable-reported", passed, passed
                ? BLOCKED_CASES.size() + " blocked cases (corrupt bytes, foreign scope, foreign evaluation policy,"
                        + " export behind the checkpoint) each reported exactly their blockers and first plan action,"
                        + " and none was rebuilt"
                : String.join("; ", failures));
    }

    private RecoveryProbe probe(List<RecoveryProbe> probes, String caseId) {
        return probes.stream().filter(p -> p.caseId().equals(caseId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no restart case " + caseId));
    }

    private Set<BlockerKind> blockerKinds(RecoveryAssessment assessment) {
        return switch (assessment) {
            case RecoveryAssessment.Compatible ignored -> Set.of();
            case RecoveryAssessment.Blocked blocked -> blocked.blockers().stream().map(RecoveryBlocker::kind)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(BlockerKind.class)));
        };
    }

    /** The #67 static wiring check over every host scenario's static run; passes when it passes for each. */
    private Check staticWiringCheck(List<HostRun> scenarios) {
        var failures = new ArrayList<String>();
        int decisions = 0;
        for (HostRun scenario : scenarios) {
            Check check = RoutingReplayEvaluation.staticWiringCheck(scenario.fixture(),
                    scenario.runs().get(ReplayArm.STATIC));
            decisions += scenario.fixture().tasks().size();
            if (!check.passed()) {
                failures.add(scenario.name() + ": " + check.detail());
            }
        }
        boolean passed = failures.isEmpty();
        return new Check("control.static-wiring", passed, passed
                ? decisions + " static decisions over " + scenarios.size() + " host scenarios equal the policy's"
                        + " decisions over an empty preference, with no state, delivery or derivation"
                : String.join("; ", failures));
    }

    /** The #67 bounds check over every host scenario's fixture and adaptive run; passes when it passes for each. */
    private Check boundsCheck(List<HostRun> scenarios) {
        var failures = new ArrayList<String>();
        var details = new ArrayList<String>();
        for (HostRun scenario : scenarios) {
            Check check = RoutingReplayEvaluation.boundsCheck(scenario.fixture(),
                    scenario.runs().get(ReplayArm.ADAPTIVE));
            details.add(scenario.name() + "[" + check.detail() + "]");
            if (!check.passed()) {
                failures.add(scenario.name() + ": " + check.detail());
            }
        }
        boolean passed = failures.isEmpty();
        return new Check("bounds.respected", passed, passed ? String.join("; ", details)
                : String.join("; ", failures));
    }

    /** The scenario's fixture, decision, feedback and state digests per arm and its receipt chain digest. */
    private void fingerprint(HostRun scenario, Map<String, String> metadata) {
        String prefix = "host.digest." + scenario.name() + ".";
        metadata.put(prefix + "fixture", digests.fixture(scenario.fixture()));
        for (ReplayArm arm : ARMS) {
            ReplayRun run = scenario.runs().get(arm);
            String armPrefix = prefix + arm.benchmarkName() + ".";
            metadata.put(armPrefix + "decisions", digests.decisions(run));
            metadata.put(armPrefix + "feedback", digests.feedback(run));
            run.state().ifPresent(state -> metadata.put(armPrefix + "state", digests.state(state)));
            metadata.put(armPrefix + "receiptChain", hostDigests.receiptChain(scenario.records().get(arm)));
        }
    }

    /**
     * Two fresh builds and runs of the whole suite give identical fixture, decision, feedback and state digests, the
     * same receipt chain and recovery digests, every metric line and the same check results. Vacuous unless a receipt
     * chain and a recovery digest were computed.
     */
    static Check deterministicCheck(List<Check> firstChecks, Map<String, String> first, List<Check> secondChecks,
            Map<String, String> second) {
        var failures = new ArrayList<String>();
        if (first.keySet().stream().noneMatch(k -> k.endsWith(".receiptChain"))
                || !first.containsKey("host.digest.recovery")) {
            failures.add("no receipt chain or recovery digest was computed, so the check is vacuous");
        }
        if (!firstChecks.equals(secondChecks)) {
            var differing = new ArrayList<String>();
            for (int i = 0; i < Math.max(firstChecks.size(), secondChecks.size()); i++) {
                Check a = i < firstChecks.size() ? firstChecks.get(i) : null;
                Check b = i < secondChecks.size() ? secondChecks.get(i) : null;
                if (a == null || !a.equals(b)) {
                    differing.add(a != null ? a.name() : b.name());
                }
            }
            failures.add("the runs' checks differ in " + differing);
        }
        if (!first.equals(second)) {
            var differing = new TreeMap<String, String>();
            first.forEach((k, v) -> {
                if (!v.equals(second.get(k))) {
                    differing.put(k, v + " vs " + second.get(k));
                }
            });
            second.keySet().stream().filter(k -> !first.containsKey(k)).forEach(k -> differing.put(k, "only second"));
            failures.add("the runs differ in " + differing.size() + " lines, first " + differing.firstEntry());
        }
        boolean passed = failures.isEmpty();
        return new Check("host.deterministic", passed, passed
                ? "two fresh builds and runs gave identical lines for all " + first.size() + " metadata entries,"
                        + " including every fixture, decision, feedback, state, receipt chain and recovery digest"
                : String.join("; ", failures));
    }

    /**
     * The catalog probe: task {@value HostScenarioFixtures#CATALOG_SWITCH_TASK}'s request decided over an empty
     * preference under {@code host-cat/1} and under {@code host-cat/2}, with the same trusted profile, compared by
     * {@link ScenarioComparator}. Passes when the comparison is {@code Compared} with {@code CATALOG} among its
     * differences and {@code sub-b} standing {@code NotInCatalog} after, and no decision after the switch selects
     * {@code sub-b} in any arm.
     */
    Check catalogChangeCheck(HostRun scenario, Map<String, String> metadata) {
        ReplayFixture fixture = scenario.fixture();
        RoutingRequest request = fixture.tasks().get(HostScenarioFixtures.CATALOG_SWITCH_TASK).request();
        var profile = TrustedRoutingProfile.lexicographic(fixture.policy());
        var preference = RoutingPreference.empty(request, fixture.definition().state(), fixture.policy().policyId(),
                fixture.policy().policyVersion());
        ScenarioComparison comparison = new ScenarioComparator().compare(
                new RoutingScenario(fixture.policy(), profile,
                        new RoutingInput(request, HostScenarioFixtures.CATALOG_1, preference)),
                new RoutingScenario(fixture.policy(), profile,
                        new RoutingInput(request, HostScenarioFixtures.CATALOG_2, preference)));
        var failures = new ArrayList<String>();
        String prefix = "host.catalog-change.probe.";
        metadata.put(prefix + "comparison", comparison.getClass().getSimpleName());
        switch (comparison) {
            case ScenarioComparison.Compared compared -> {
                metadata.put(prefix + "differences", compared.differences().toString());
                metadata.put(prefix + "routeChange", compared.delta().routeChange().name());
                for (EligibilityChange change : compared.delta().eligibilityChanges()) {
                    metadata.put(prefix + "eligibility." + change.route().routeId() + "@" + change.route().routeVersion(),
                            change.before().getClass().getSimpleName()
                            + "->" + change.after().getClass().getSimpleName());
                }
                if (!compared.differences().contains(ScenarioDifference.CATALOG)) {
                    failures.add("CATALOG is not among the differences " + compared.differences());
                }
                boolean subBGone = compared.delta().eligibilityChanges().stream().anyMatch(c ->
                        c.route().equals(HostScenarioFixtures.SUB_B) && c.after() instanceof RouteStanding.NotInCatalog);
                if (!subBGone) {
                    failures.add("sub-b does not stand NotInCatalog after: " + compared.delta().eligibilityChanges());
                }
            }
            case ScenarioComparison.Incomparable incomparable ->
                    failures.add("the catalog change made the scenarios incomparable: " + incomparable.differences());
        }
        for (ReplayArm arm : ARMS) {
            List<ReplaySample> samples = scenario.runs().get(arm).samples();
            for (int i = HostScenarioFixtures.CATALOG_SWITCH_TASK; i < samples.size(); i++) {
                if (samples.get(i).route().filter(HostScenarioFixtures.SUB_B::equals).isPresent()) {
                    failures.add(arm + " selected sub-b at task " + i + " after it left the catalog");
                }
            }
        }
        boolean passed = failures.isEmpty();
        return new Check("host.catalog-change.compared", passed, passed
                ? "the probe compared host-cat/1 with host-cat/2 (" + metadata.get(prefix + "differences")
                        + ", route change " + metadata.get(prefix + "routeChange") + "), sub-b stands NotInCatalog"
                        + " after, and no decision after the switch selects it"
                : String.join("; ", failures));
    }

    /**
     * A scenario and its two leakage variants around the cutoff of task {@value HostScenarioFixtures#LEAKAGE_TASK}:
     * every outcome and correction visible at or after it flipped, and every task decided after it removed.
     */
    record LeakageVariants(HostRun original, HostRun flipped, HostRun truncated, long cutoff) {
    }

    /** Builds and runs the flipped and truncated variants of a scenario, each with the scenario's own script. */
    LeakageVariants leakageVariants(HostRun scenario) {
        long cutoff = fixtures.cutoff(HostScenarioFixtures.LEAKAGE_TASK);
        var synthetic = new SyntheticRoutingFixtures();
        Map<Integer, HostAction> script = Map.of();
        return new LeakageVariants(scenario,
                run(scenario.name() + "+flipped", synthetic.futureFlipped(scenario.fixture(), cutoff), script),
                run(scenario.name() + "+truncated", synthetic.truncated(scenario.fixture(), cutoff + 1), script),
                cutoff);
    }

    /**
     * Future evidence never enters an earlier decision: in every arm, each decision at or before the split cutoff, its
     * snapshot watermark and how many observations it saw are the same with later evidence flipped or the later tasks
     * removed. Vacuous unless flipping changes at least one later adaptive decision.
     */
    Check futureLeakageCheck(List<LeakageVariants> all) {
        var failures = new ArrayList<String>();
        int compared = 0;
        int changedLater = 0;
        for (LeakageVariants variants : all) {
            for (ReplayArm arm : ARMS) {
                ReplayRun original = variants.original().runs().get(arm);
                for (HostRun variant : List.of(variants.flipped(), variants.truncated())) {
                    ReplayRun other = variant.runs().get(arm);
                    for (int i = 0; i < original.samples().size(); i++) {
                        ReplaySample sample = original.samples().get(i);
                        if (sample.cutoff() > variants.cutoff()) {
                            continue;
                        }
                        compared++;
                        ReplaySample changed = other.samples().get(i);
                        if (!sample.decisionProjection().equals(changed.decisionProjection())
                                || sample.stateCutoff() != changed.stateCutoff()
                                || original.decisions().visible()[i] != other.decisions().visible()[i]) {
                            failures.add(variant.name() + " " + arm + " changed decision " + i + " at or before the"
                                    + " cutoff");
                        }
                    }
                }
            }
            ReplayRun adaptive = variants.original().runs().get(ReplayArm.ADAPTIVE);
            ReplayRun flipped = variants.flipped().runs().get(ReplayArm.ADAPTIVE);
            for (int i = 0; i < adaptive.samples().size(); i++) {
                if (adaptive.samples().get(i).cutoff() > variants.cutoff() && !adaptive.samples().get(i)
                        .decisionProjection().equals(flipped.samples().get(i).decisionProjection())) {
                    changedLater++;
                }
            }
        }
        if (changedLater == 0) {
            failures.add("flipping later evidence changed no later adaptive decision, so the check is vacuous");
        }
        boolean passed = failures.isEmpty();
        return new Check("host.leakage.future-evidence", passed, passed
                ? compared + " decisions at or before the split cutoff are unchanged by flipped or truncated later"
                        + " evidence in every arm, while " + changedLater + " later adaptive decisions changed"
                : String.join("; ", failures));
    }

    /**
     * Foreign evidence never enters the state. After the scenario's adaptive run, three observations that differ from
     * its first delivered one only in scope, evaluation policy version or feature schema version are derived with the
     * real decision and request: each derives {@code NoUpdate} with its named reason, each {@code consume} leaves the
     * state unchanged, and the state digest after all three equals the digest before, so no decision can read them.
     */
    Check foreignEvidenceCheck(HostRun scenario, Map<String, String> metadata) {
        ReplayRun run = scenario.runs().get(ReplayArm.ADAPTIVE);
        RoutingStateStore state = run.state().orElseThrow();
        var policy = scenario.fixture().definition().feedbackPolicy(scenario.fixture().evidencePolicy());
        ReplayRun.Delivered real = run.delivered().getFirst();
        RouteKey route = real.observation().outcome().executedRoute();
        ReplayTask task = scenario.fixture().tasks().stream()
                .filter(t -> t.request().equals(real.request())).findFirst().orElseThrow();
        ScriptedOutcome scripted = ((OutcomeOracle.KnownForEveryRoute) task.oracle()).outcomeOf(route).orElseThrow();
        RoutingRequest request = real.request();
        CohortBinding binding = real.decision().cohortBinding();
        var foreign = new LinkedHashMap<String, RoutingObservation>();
        foreign.put("scope", observations.observe(fixtures.foreignRequest(request,
                HostScenarioFixtures.FOREIGN_SCOPE, request.evaluationPolicyVersion()), real.decision(), scripted, 0,
                route, binding));
        foreign.put("evaluation-policy", observations.observe(fixtures.foreignRequest(request, request.scopeId(),
                HostScenarioFixtures.FOREIGN_EVALUATION_VERSION), real.decision(), scripted, 0, route, binding));
        foreign.put("feature-schema", observations.observe(request, real.decision(), scripted, 0, route,
                new CohortBinding(binding.cohortBucket(), binding.bucketMappingVersion(), binding.mappingVersion(),
                        binding.featureSchemaVersion() + "-foreign")));
        Map<String, NoUpdateReason> expected = Map.of("scope", NoUpdateReason.BINDING_SCOPE_MISMATCH,
                "evaluation-policy", NoUpdateReason.BINDING_PARTITION_MISMATCH,
                "feature-schema", NoUpdateReason.FEATURE_SCHEMA_MISMATCH);
        String before = digests.state(state);
        var failures = new ArrayList<String>();
        foreign.forEach((name, observation) -> {
            FeedbackDerivation derivation = policy.derive(observation, real.decision(), request, state.bindings());
            metadata.put("host.leakage.foreign." + name + ".derivation", derivation instanceof
                    FeedbackDerivation.NoUpdate none ? "NoUpdate(" + none.reason() + ")" : derivation.toString());
            if (!(derivation instanceof FeedbackDerivation.NoUpdate none) || none.reason() != expected.get(name)) {
                failures.add(name + " derived " + derivation + ", not NoUpdate(" + expected.get(name) + ")");
            }
            StateUpdate update = state.consume(observation, derivation);
            metadata.put("host.leakage.foreign." + name + ".update", update.getClass().getSimpleName());
            if (!(update instanceof StateUpdate.Unchanged)) {
                failures.add(name + " was consumed as " + update);
            }
        });
        if (!before.equals(digests.state(state))) {
            failures.add("the state digest changed");
        }
        boolean passed = failures.isEmpty();
        return new Check("host.leakage.foreign-evidence", passed, passed
                ? "foreign scope, evaluation policy and feature schema observations derived NoUpdate"
                        + " (BINDING_SCOPE_MISMATCH, BINDING_PARTITION_MISMATCH, FEATURE_SCHEMA_MISMATCH), were each"
                        + " consumed as Unchanged, and the state digest is unchanged"
                : String.join("; ", failures));
    }

    /**
     * Static and adaptive arms report only observed data. Per scenario and arm: every sample that is not
     * {@code OBSERVED} carries no outcome, acceptance or cost; a feedback event exists only for a task whose execution
     * is {@code Advised} or {@code Alternative}, and its executed route is that execution's route; no observation names
     * a route the host did not run; the alternative row summarizes exactly the alternative executions and the advised
     * row every other decision. Vacuous unless some sample was observed, some selection was left unexecuted and some
     * alternative ran.
     */
    Check observedOnlyCheck(List<HostRun> scenarios) {
        var failures = new ArrayList<String>();
        int observed = 0;
        int hostNotExecuted = 0;
        int alternatives = 0;
        for (HostRun scenario : scenarios) {
            for (ReplayArm arm : ARMS) {
                ReplayRun run = scenario.runs().get(arm);
                List<HostDecisionRecord> records = scenario.records().get(arm);
                Map<String, Optional<RouteKey>> executedByRef = new HashMap<>();
                for (int i = 0; i < records.size(); i++) {
                    HostDecisionRecord record = records.get(i);
                    ReplaySample sample = run.samples().get(i);
                    Optional<RouteKey> executed = switch (record.execution()) {
                        case HostExecution.Advised ignored -> sample.route();
                        case HostExecution.Alternative alternative -> Optional.of(alternative.route());
                        case HostExecution.NotExecuted ignored -> Optional.empty();
                    };
                    executedByRef.put(refOf(scenario.fixture(), record), executed);
                    if (sample.coverage() == ReplaySample.Coverage.OBSERVED) {
                        observed++;
                    } else if (sample.acceptance().isPresent() || sample.chainCost().isPresent()
                            || sample.verdict().isPresent() || sample.attempts() != 0) {
                        failures.add(scenario.name() + " " + arm + " task " + i + " is " + sample.coverage()
                                + " but carries an outcome");
                    }
                    if (sample.coverage() == ReplaySample.Coverage.HOST_NOT_EXECUTED) {
                        hostNotExecuted++;
                    }
                }
                Predicate<HostDecisionRecord> alternative = r -> r.execution() instanceof HostExecution.Alternative;
                int expected = (int) records.stream().filter(alternative).count();
                int alternativeRow = decisionsIn(rows(scenario.fixture(), run, records, alternative));
                int advisedRow = decisionsIn(rows(scenario.fixture(), run, records, alternative.negate()));
                alternatives += expected;
                if (alternativeRow != expected || advisedRow + alternativeRow != records.size()) {
                    failures.add(scenario.name() + " " + arm + " rows hold " + advisedRow + " advised and "
                            + alternativeRow + " alternative decisions, expected " + (records.size() - expected)
                            + " and " + expected);
                }
                for (FeedbackEvent event : run.feedback()) {
                    Optional<RouteKey> executed = executedByRef.getOrDefault(event.experienceRef(), Optional.empty());
                    if (executed.isEmpty() || !executed.get().equals(event.executedRoute())) {
                        failures.add(scenario.name() + " " + arm + " feedback for " + event.experienceRef()
                                + " names " + event.executedRoute() + ", but the host ran " + executed);
                    }
                }
                for (ReplayRun.Delivered delivered : run.delivered()) {
                    Optional<RouteKey> executed = executedByRef.getOrDefault(delivered.observation().experienceRef(),
                            Optional.empty());
                    if (!executed.equals(Optional.of(delivered.observation().outcome().executedRoute()))) {
                        failures.add(scenario.name() + " " + arm + " generated an outcome for a route the host did"
                                + " not run");
                    }
                }
            }
        }
        if (observed == 0 || hostNotExecuted == 0 || alternatives == 0) {
            failures.add("observed=" + observed + ", hostNotExecuted=" + hostNotExecuted + ", alternatives="
                    + alternatives + ": the check is vacuous");
        }
        boolean passed = failures.isEmpty();
        return new Check("host.arms.observed-only", passed, passed
                ? observed + " observed samples carry the only outcomes and costs; " + hostNotExecuted + " selections"
                        + " the host left unexecuted carry none; every feedback event and observation names the route"
                        + " the host ran; the " + alternatives + " alternative executions are summarized in their own"
                        + " row"
                : String.join("; ", failures));
    }

    /** The number of decisions a summary row counts, summed over its coverage lines. */
    private int decisionsIn(Map<String, String> row) {
        return row.entrySet().stream().filter(e -> e.getKey().startsWith("total.coverage."))
                .mapToInt(e -> Integer.parseInt(e.getValue())).sum();
    }

    /** Runs a scenario under every arm, each with a fresh replay and script. */
    HostRun run(String name, ReplayFixture fixture, Map<Integer, HostAction> script) {
        var runs = new EnumMap<ReplayArm, ReplayRun>(ReplayArm.class);
        var records = new EnumMap<ReplayArm, List<HostDecisionRecord>>(ReplayArm.class);
        for (ReplayArm arm : ARMS) {
            var host = new HostScript(fixture, script, HostScenarioFixtures.REQUIRED_FOR_PREVIEW);
            runs.put(arm, new TemporalReplay(fixture, host).run(arm));
            records.put(arm, host.records());
        }
        return new HostRun(name, fixture, runs, records);
    }

    /**
     * The adaptive run of the same script where every alternative runs nothing instead: the reference an override is
     * compared with, since an override derives a recordable {@code PROVENANCE_MISMATCH} and leaves a ledger marker.
     */
    private ReplayRun runWithoutAlternatives(HostRun scenario) {
        var host = new HostScript(scenario.fixture(), fixtures.dispositionScript(),
                HostScenarioFixtures.REQUIRED_FOR_PREVIEW);
        HostStep withoutAlternatives = (task, decision) -> switch (host.execute(task, decision)) {
            case HostExecution.Alternative ignored -> new HostExecution.NotExecuted(HostAction.ALTERNATIVE);
            case HostExecution execution -> execution;
        };
        return new TemporalReplay(scenario.fixture(), withoutAlternatives).run(ReplayArm.ADAPTIVE);
    }

    /**
     * A disposition alone earns no credit: on the adaptive run every applied update answers a followed task that ran
     * the advice, and every task the host ran nothing for has no observation and no feedback event. Vacuous unless
     * every disposition variant bound at least once, one was rejected, an alternative ran over a selection, one was
     * bound over an abstention without running, and at least one credit landed.
     */
    Check noCreditPathCheck(HostRun scenario) {
        ReplayRun run = scenario.runs().get(ReplayArm.ADAPTIVE);
        List<HostDecisionRecord> records = scenario.records().get(ReplayArm.ADAPTIVE);
        Map<String, HostDecisionRecord> byRef = byExperienceRef(scenario.fixture(), records);
        var failures = new ArrayList<String>();
        int applied = 0;
        for (FeedbackEvent event : run.feedback()) {
            if (event.update() != FeedbackEvent.Update.APPLIED) {
                continue;
            }
            applied++;
            HostDecisionRecord record = byRef.get(event.experienceRef());
            if (record == null || record.action() != HostAction.FOLLOW
                    || !(record.execution() instanceof HostExecution.Advised)) {
                failures.add("an applied update answers task " + (record == null ? "?" : record.taskIndex()
                        + " (" + record.action() + ", " + record.execution() + ")"));
            }
        }
        for (HostDecisionRecord record : records) {
            if (record.execution() instanceof HostExecution.NotExecuted) {
                String ref = refOf(scenario.fixture(), record);
                if (run.delivered().stream().anyMatch(d -> d.observation().experienceRef().equals(ref))
                        || run.feedback().stream().anyMatch(e -> e.experienceRef().equals(ref))) {
                    failures.add("task " + record.taskIndex() + " ran nothing but has an observation");
                }
            }
        }
        var bound = new TreeMap<String, Integer>();
        var rejected = new TreeMap<String, Integer>();
        for (HostDecisionRecord record : records) {
            record.disposition().ifPresent(result -> {
                switch (result) {
                    case DispositionResult.Bound b -> bound.merge(variant(b.disposition()), 1, Integer::sum);
                    case DispositionResult.Rejected r -> rejected.merge(r.reason().name(), 1, Integer::sum);
                }
            });
        }
        if (bound.size() < 4) {
            failures.add("not every disposition variant was bound: " + bound);
        }
        boolean ranOverSelection = records.stream().anyMatch(r -> r.execution() instanceof HostExecution.Alternative);
        boolean boundOverAbstention = records.stream().anyMatch(r -> r.kind() == ReplaySample.DecisionKind.ABSTAINED
                && r.action() == HostAction.ALTERNATIVE
                && r.disposition().filter(DispositionResult.Bound.class::isInstance).isPresent()
                && r.execution() instanceof HostExecution.NotExecuted);
        if (!ranOverSelection || !boundOverAbstention) {
            failures.add("an alternative ran over a selection: " + ranOverSelection
                    + ", one was bound over an abstention and ran nothing: " + boundOverAbstention);
        }
        if (rejected.isEmpty()) {
            failures.add("no disposition was rejected");
        }
        if (applied == 0) {
            failures.add("no credit landed, so the check is vacuous");
        }
        boolean passed = failures.isEmpty();
        return new Check("host.disposition.no-credit-path", passed, passed
                ? applied + " applied updates all answer followed tasks that ran the advice; every task the host ran"
                        + " nothing for has no observation; bound=" + bound + ", rejected=" + rejected
                : String.join("; ", failures));
    }

    /**
     * An override credits only the executed route under existing rules, which in v1 is nothing: every alternative
     * execution was recorded by the run as {@code NoUpdate(PROVENANCE_MISMATCH)}, derives that again naming
     * {@code EXECUTED_ROUTE} (the mismatch fields are not recorded), and moves no cohort Node or support compared with
     * the same run where it ran nothing. Vacuous unless at least one alternative ran.
     */
    Check executedRouteOnlyCheck(HostRun scenario, ReplayRun withoutAlternatives) {
        ReplayRun run = scenario.runs().get(ReplayArm.ADAPTIVE);
        RoutingStateStore state = run.state().orElseThrow();
        var policy = scenario.fixture().definition().feedbackPolicy(scenario.fixture().evidencePolicy());
        var failures = new ArrayList<String>();
        var advised = new HashMap<String, RouteKey>();
        int alternatives = 0;
        for (ReplayRun.Delivered delivered : run.delivered()) {
            advised.put(delivered.observation().experienceRef(), delivered.decision().route());
            if (delivered.observation().outcome().executedRoute().equals(delivered.decision().route())) {
                continue;
            }
            alternatives++;
            String ref = delivered.observation().experienceRef();
            if (run.feedback().stream().filter(e -> e.experienceRef().equals(ref)).findAny().isEmpty()
                    || run.feedback().stream().filter(e -> e.experienceRef().equals(ref))
                            .anyMatch(e -> !e.noUpdate().equals(Optional.of(NoUpdateReason.PROVENANCE_MISMATCH)))) {
                failures.add(ref + " was not recorded as NoUpdate(PROVENANCE_MISMATCH) by the run");
            }
            FeedbackDerivation derivation = policy.derive(delivered.observation(), delivered.decision(),
                    delivered.request(), state.bindings());
            if (!(derivation instanceof FeedbackDerivation.NoUpdate none)
                    || none.reason() != NoUpdateReason.PROVENANCE_MISMATCH
                    || !none.mismatches().contains(ProvenanceField.EXECUTED_ROUTE)) {
                failures.add(delivered.observation().experienceRef() + " derived " + derivation);
            }
        }
        for (FeedbackEvent event : run.feedback()) {
            if (!event.executedRoute().equals(advised.get(event.experienceRef())) && (event.credits()
                    || event.update() == FeedbackEvent.Update.APPLIED)) {
                failures.add(event.experienceRef() + " credited an override: " + event.update());
            }
        }
        String withState = digests.cohorts(state);
        String without = digests.cohorts(withoutAlternatives.state().orElseThrow());
        if (!withState.equals(without)) {
            failures.add("the overrides moved a cohort Node or its support");
        }
        if (alternatives == 0) {
            failures.add("no alternative ran, so the check is vacuous");
        }
        boolean passed = failures.isEmpty();
        return new Check("host.override.executed-route-only", passed, passed
                ? alternatives + " alternative executions each derived NoUpdate(PROVENANCE_MISMATCH) naming"
                        + " EXECUTED_ROUTE, and the cohorts equal the run where they ran nothing: under v1 an override"
                        + " credits no route"
                : String.join("; ", failures));
    }

    /**
     * Per scenario and arm: the host's action, disposition and execution counts and receipt sizes, and the replay
     * summary with alternative samples in their own row, so their quality never mixes with the advised route's.
     */
    private void describe(HostRun scenario, Map<String, String> metadata) {
        for (ReplayArm arm : ARMS) {
            String prefix = "host." + scenario.name() + "." + arm.benchmarkName() + ".";
            ReplayRun run = scenario.runs().get(arm);
            List<HostDecisionRecord> records = scenario.records().get(arm);
            var counts = new TreeMap<String, Integer>();
            int minBytes = Integer.MAX_VALUE;
            int maxBytes = 0;
            long totalBytes = 0;
            for (HostDecisionRecord record : records) {
                counts.merge("action." + record.action(), 1, Integer::sum);
                counts.merge("execution." + record.execution().getClass().getSimpleName(), 1, Integer::sum);
                record.missingForHostPreview().forEach(d -> counts.merge("readiness.missing." + d, 1, Integer::sum));
                record.abstain().ifPresent(r -> counts.merge("abstained." + r, 1, Integer::sum));
                record.disposition().ifPresent(result -> counts.merge(switch (result) {
                    case DispositionResult.Bound b -> "disposition.bound." + variant(b.disposition());
                    case DispositionResult.Rejected r -> "disposition.rejected." + r.reason();
                }, 1, Integer::sum));
                minBytes = Math.min(minBytes, record.receiptBytes());
                maxBytes = Math.max(maxBytes, record.receiptBytes());
                totalBytes += record.receiptBytes();
            }
            counts.forEach((k, v) -> metadata.put(prefix + k, Integer.toString(v)));
            metadata.put(prefix + "credits.applied", Long.toString(run.feedback().stream()
                    .filter(e -> e.update() == FeedbackEvent.Update.APPLIED).count()));
            metadata.put(prefix + "receipt.bytes.min", Integer.toString(records.isEmpty() ? 0 : minBytes));
            metadata.put(prefix + "receipt.bytes.max", Integer.toString(maxBytes));
            metadata.put(prefix + "receipt.bytes.total", Long.toString(totalBytes));
            Predicate<HostDecisionRecord> alternative = r -> r.execution() instanceof HostExecution.Alternative;
            rows(scenario.fixture(), run, records, alternative.negate())
                    .forEach((k, v) -> metadata.put(prefix + "advised." + k, v));
            rows(scenario.fixture(), run, records, alternative)
                    .forEach((k, v) -> metadata.put(prefix + "alternative." + k, v));
        }
    }

    /** The replay summary over the samples and events of the records the filter keeps. */
    private Map<String, String> rows(ReplayFixture fixture, ReplayRun run, List<HostDecisionRecord> records,
            Predicate<HostDecisionRecord> keep) {
        var refs = new HashMap<String, Boolean>();
        var samples = new ArrayList<ReplaySample>();
        for (int i = 0; i < records.size(); i++) {
            boolean kept = keep.test(records.get(i));
            refs.put(refOf(fixture, records.get(i)), kept);
            if (kept) {
                samples.add(run.samples().get(i));
            }
        }
        List<FeedbackEvent> events = run.feedback().stream()
                .filter(e -> refs.getOrDefault(e.experienceRef(), false)).toList();
        var subset = new ReplayRun(run.arm(), samples, events, List.of(), run.decisions(), run.derivations(),
                Optional.empty());
        String armPrefix = run.arm().benchmarkName() + ".";
        var lines = new TreeMap<String, String>();
        ReplaySummary.of(subset).lines().forEach((k, v) -> lines.put(k.substring(armPrefix.length()), v));
        // quality over observed samples only; a sample with no outcome is reported as such, never as a failure
        var quality = new TreeMap<String, Integer>(Map.of("validatedAccepted", 0, "notAccepted", 0,
                "unvalidatedOrPending", 0, "noOutcome", 0));
        for (ReplaySample sample : samples) {
            String bucket = sample.acceptance().map(a -> switch (a) {
                case VALIDATED_ACCEPTED -> "validatedAccepted";
                case NOT_ACCEPTED -> "notAccepted";
                case ACCEPTED_UNVALIDATED, PENDING -> "unvalidatedOrPending";
            }).orElse("noOutcome");
            quality.merge(bucket, 1, Integer::sum);
        }
        quality.forEach((k, v) -> lines.put("quality." + k, Integer.toString(v)));
        return lines;
    }

    private Map<String, HostDecisionRecord> byExperienceRef(ReplayFixture fixture, List<HostDecisionRecord> records) {
        var byRef = new HashMap<String, HostDecisionRecord>();
        records.forEach(r -> byRef.put(refOf(fixture, r), r));
        return byRef;
    }

    private String refOf(ReplayFixture fixture, HostDecisionRecord record) {
        return observations.experienceRef(fixture.tasks().get(record.taskIndex()).request());
    }

    private String variant(AdviceDisposition disposition) {
        return switch (disposition) {
            case AdviceDisposition.Followed ignored -> "FOLLOWED";
            case AdviceDisposition.AlternativeProposed ignored -> "ALTERNATIVE_PROPOSED";
            case AdviceDisposition.Deferred ignored -> "DEFERRED";
            case AdviceDisposition.Declined ignored -> "DECLINED";
        };
    }
}
