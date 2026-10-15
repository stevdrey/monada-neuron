package monada.neuron.evaluation.routing;

import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.outcome.Money;
import monada.neuron.routing.outcome.UsageDeclaration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingReplayEvaluationTest {

    private static List<RoutingReplayEvaluation.Check> checks;

    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();

    @BeforeAll
    static void verifyOnce() {
        checks = new RoutingReplayEvaluation(true).verify();
    }

    @Test
    void everySemanticCheckPasses() {
        var failures = checks.stream().filter(c -> !c.passed()).map(c -> c.name() + ": " + c.detail()).toList();
        assertEquals(List.of(), failures);
    }

    @Test
    void coversEveryRequiredGate() {
        var names = checks.stream().map(RoutingReplayEvaluation.Check::name).toList();
        assertTrue(names.containsAll(List.of("replay.deterministic", "leakage.future-outcomes",
                "leakage.late-correction", "leakage.snapshot-watermark", "control.derived-not-consumed-equals-static",
                "control.static-wiring", "control.neutral-feedback-leaves-state-unchanged",
                "eligibility.violations-zero", "state.incremental-equals-rebuild", "cases.cheap-invalid",
                "cases.repaired-accepted", "cases.unknown-usage", "cases.incomparable-context-and-stage",
                "cases.currencies-not-summed", "cases.abstention-and-no-eligible-route",
                "cases.missing-counterfactual", "cost.completeness-consistent", "adaptation.improvement-and-degradation-visible",
                "bounds.respected")), names.toString());
    }

    @Test
    void verdictsAndMetadataAreStableAcrossRuns() {
        var again = new RoutingReplayEvaluation(true).verify();
        assertEquals(checks, again);
    }

    @Test
    void determinismCheckRejectsEmptyAndDifferingFingerprints() {
        var replayed = new TreeMap<>(Map.of("a.samples", "3", "report", "x"));
        var differing = new TreeMap<>(Map.of("a.samples", "3", "report", "y"));
        assertAll(
                () -> assertTrue(RoutingReplayEvaluation.determinismCheck(replayed, replayed).passed()),
                // identical but nothing replayed
                () -> assertFalse(RoutingReplayEvaluation.determinismCheck(Map.of("a.samples", "0"),
                        Map.of("a.samples", "0")).passed()),
                () -> assertFalse(RoutingReplayEvaluation.determinismCheck(replayed, differing).passed()));
    }

    @Test
    void theDerivedNotConsumedControlIsVacuousWithoutDerivedFeedback() {
        var control = runs(fixtures.improvement(8), ReplayArm.STATIC);
        // a static run in both positions: identical decisions, but no feedback was ever derived
        var vacuous = new EnumMap<ReplayArm, ReplayRun>(ReplayArm.class);
        vacuous.put(ReplayArm.STATIC, control.get(ReplayArm.STATIC));
        vacuous.put(ReplayArm.DERIVED_NOT_CONSUMED, control.get(ReplayArm.STATIC));
        var real = runs(fixtures.improvement(24), ReplayArm.STATIC, ReplayArm.DERIVED_NOT_CONSUMED);
        // the adaptive arm in the derived-only position: it does change decisions
        var consumed = new EnumMap<ReplayArm, ReplayRun>(ReplayArm.class);
        consumed.put(ReplayArm.STATIC, real.get(ReplayArm.STATIC));
        consumed.put(ReplayArm.DERIVED_NOT_CONSUMED, runs(fixtures.improvement(24), ReplayArm.ADAPTIVE)
                .get(ReplayArm.ADAPTIVE));

        assertAll(
                () -> assertTrue(RoutingReplayEvaluation.derivedNotConsumedCheck(List.of(real)).passed()),
                () -> assertFalse(RoutingReplayEvaluation.derivedNotConsumedCheck(List.of(vacuous)).passed()),
                () -> assertFalse(RoutingReplayEvaluation.derivedNotConsumedCheck(List.of(consumed)).passed()));
    }

    @Test
    void theLeakageCheckFailsWhenAMutationCannotChangeAnything() {
        var improvement = fixtures.improvement(24);
        var original = runs(improvement, ReplayArm.values());
        long cutoff = fixtures.cutoff(RoutingReplayEvaluation.LEAKAGE_TASK);
        var truncated = runs(fixtures.truncated(improvement, cutoff), ReplayArm.values());

        assertAll(
                () -> assertTrue(RoutingReplayEvaluation.futureLeakageCheck(original,
                        runs(fixtures.futureFlipped(improvement, cutoff), ReplayArm.values()), truncated, cutoff)
                        .passed()),
                // the original in place of the mutation: earlier decisions are trivially equal
                () -> assertFalse(RoutingReplayEvaluation.futureLeakageCheck(original, original, truncated, cutoff)
                        .passed()));
    }

    @Test
    void theVisibilityCheckDoesNotPassOnANullEffect() {
        var flat = ReplaySummary.of(runs(fixtures.neutral(16), ReplayArm.STATIC).get(ReplayArm.STATIC));
        var improvement = runs(fixtures.improvement(24), ReplayArm.STATIC, ReplayArm.ADAPTIVE);
        var degradation = runs(fixtures.degradation(24), ReplayArm.STATIC, ReplayArm.ADAPTIVE);

        assertAll(
                () -> assertTrue(RoutingReplayEvaluation.visibilityCheck(
                        ReplaySummary.of(improvement.get(ReplayArm.STATIC)),
                        ReplaySummary.of(improvement.get(ReplayArm.ADAPTIVE)),
                        ReplaySummary.of(degradation.get(ReplayArm.STATIC)),
                        ReplaySummary.of(degradation.get(ReplayArm.ADAPTIVE))).passed()),
                () -> assertFalse(RoutingReplayEvaluation.visibilityCheck(flat, flat, flat, flat).passed()));
    }

    @Test
    void aMeasuredReplayIsRejectedWhenItsDecisionsDeliveriesOrStateDiffer() {
        var replay = new TemporalReplay(fixtures.improvement(24));
        var digests = new ReplayDigests();
        var reference = replay.run(ReplayArm.ADAPTIVE, false);

        assertAll(
                () -> RoutingReplayEvaluation.requireSameReplay(replay.run(ReplayArm.ADAPTIVE, false), reference,
                        digests),
                // the static arm delivers nothing and keeps no state; its different decisions alone must be caught
                () -> assertThrows(IllegalStateException.class, () -> RoutingReplayEvaluation.requireSameReplay(
                        replay.run(ReplayArm.STATIC, false), replay.run(ReplayArm.ADAPTIVE, false), digests)),
                // same decisions as static, but deliveries and state differ
                () -> assertThrows(IllegalStateException.class, () -> RoutingReplayEvaluation.requireSameReplay(
                        replay.run(ReplayArm.DERIVED_NOT_CONSUMED, false), replay.run(ReplayArm.STATIC, false),
                        digests)),
                // same size, trace and state, but one delivery duplicated in place of another
                () -> assertThrows(IllegalStateException.class, () -> {
                    var derivedOnly = replay.run(ReplayArm.DERIVED_NOT_CONSUMED, false);
                    var delivered = new ArrayList<>(derivedOnly.delivered());
                    delivered.set(1, delivered.get(0));
                    RoutingReplayEvaluation.requireSameReplay(new ReplayRun(derivedOnly.arm(), List.of(), List.of(),
                            delivered, derivedOnly.decisions(), derivedOnly.derivations(), derivedOnly.state()), derivedOnly, digests);
                }),
                // a missing final run is an error, not a silent pass
                () -> assertThrows(IllegalStateException.class,
                        () -> RoutingReplayEvaluation.requireSameReplay(null, reference, digests)));
    }

    @Test
    void theCostCompletenessCheckCatchesACompleteCostDespiteUnknownUsage() {
        var cases = runs(fixtures.cases(), ReplayArm.values());
        ReplayRun control = cases.get(ReplayArm.STATIC);
        var overstated = new ArrayList<ReplaySample>();
        for (ReplaySample s : control.samples()) {
            overstated.add(!s.decisionRef().taskId().equals("task-unknown-usage") ? s : new ReplaySample(
                    s.decisionRef(), s.stageKind(), s.cutoff(), s.stateCutoff(), s.arm(), s.kind(), s.route(),
                    s.basis(), s.abstainReason(), s.coverage(), s.acceptance(), s.verdict(), s.provenFailures(),
                    s.attempts(), s.stageUsage(), s.usageDeclaration(), Optional.of(ReplayCost.available(
                            new Money(new BigDecimal("0.0420"), "USD"))), s.comparison()));
        }
        var corrupted = new EnumMap<ReplayArm, ReplayRun>(ReplayArm.class);
        corrupted.put(ReplayArm.STATIC, new ReplayRun(ReplayArm.STATIC, overstated, control.feedback(),
                control.delivered(), control.decisions(), control.derivations(), control.state()));

        // an unrecorded MEASURED stage (no counters at all) with a complete cost must fail too
        var unrecorded = new ArrayList<ReplaySample>();
        for (ReplaySample s : control.samples()) {
            unrecorded.add(s.coverage() != ReplaySample.Coverage.OBSERVED ? s : new ReplaySample(s.decisionRef(),
                    s.stageKind(), s.cutoff(), s.stateCutoff(), s.arm(), s.kind(), s.route(), s.basis(),
                    s.abstainReason(), s.coverage(), s.acceptance(), s.verdict(), s.provenFailures(), s.attempts(),
                    List.of(), Optional.of(UsageDeclaration.MEASURED), Optional.of(ReplayCost.available(
                            new Money(new BigDecimal("0.0420"), "USD"))), s.comparison()));
        }
        var empty = new EnumMap<ReplayArm, ReplayRun>(ReplayArm.class);
        empty.put(ReplayArm.STATIC, new ReplayRun(ReplayArm.STATIC, unrecorded, control.feedback(),
                control.delivered(), control.decisions(), control.derivations(), control.state()));
        ReplaySample observed = control.samples().stream()
                .filter(s -> s.coverage() == ReplaySample.Coverage.OBSERVED).findFirst().orElseThrow();
        var declaredNone = new ReplaySample(observed.decisionRef(), observed.stageKind(), observed.cutoff(),
                observed.stateCutoff(), observed.arm(), observed.kind(), observed.route(), observed.basis(),
                observed.abstainReason(), observed.coverage(), observed.acceptance(), observed.verdict(),
                observed.provenFailures(), observed.attempts(), List.of(),
                Optional.of(UsageDeclaration.NO_BILLABLE_USAGE), observed.chainCost(), observed.comparison());

        assertAll(
                () -> assertFalse(RoutingReplayEvaluation.costCompletenessCheck(List.of(empty)).passed()),
                // explicitly declared no billable usage is complete
                () -> assertFalse(declaredNone.stageUsageIncomplete()),
                () -> assertTrue(RoutingReplayEvaluation.costCompletenessCheck(List.of(cases)).passed()),
                () -> assertFalse(RoutingReplayEvaluation.costCompletenessCheck(List.of(corrupted)).passed()),
                // no sample with unknown usage: nothing was checked
                () -> assertFalse(RoutingReplayEvaluation.costCompletenessCheck(
                        List.of(runs(fixtures.improvement(24), ReplayArm.values()))).passed()));
    }

    @Test
    void reportRendersVerdictsBeforeMeasurementsAndTheStoreArmIsNeverASilentPass() {
        var report = RoutingReplayRunner.run(true);
        var markdown = report.toMarkdown();

        assertAll(
                () -> assertTrue(report.allPassed()),
                () -> assertTrue(markdown.indexOf("## Semantic Checks") < markdown.indexOf("## Run Configuration")),
                () -> assertTrue(report.toJson().contains("\"allChecksPassed\": true")),
                () -> assertTrue(report.metadata().containsKey("fixture.workload.digest")),
                // latency is per replay iteration; every row says so and carries the derived per-decision mean
                () -> assertTrue(report.evaluation().results().stream().allMatch(r -> r.diagnostics()
                        .get("latencyScope").startsWith("per replay iteration of ")
                        && r.diagnostics().containsKey("meanNanosPerDecision"))),
                // each row's window says what that arm does: the static arm builds and binds no state
                () -> {
                    for (ReplayArm arm : ReplayArm.values()) {
                        var row = report.evaluation().results().stream()
                                .filter(r -> r.benchmarkName().equals(arm.benchmarkName())).findFirst().orElseThrow();
                        assertEquals(ReplayMeasurement.timedWindow(arm), row.diagnostics().get("timedWindow"));
                        assertEquals(Boolean.toString(arm != ReplayArm.STATIC),
                                row.diagnostics().get("stateResetPerIteration"));
                    }
                },
                () -> assertTrue(RoutingReplayRunner.storeArmUnavailable().startsWith("UNAVAILABLE")),
                () -> assertEquals(3, RoutingReplayRunner.UNAVAILABLE));
    }

    @Test
    void theReportedRunConfigurationIsTheOneTheEvaluationActuallyUses() {
        var quick = new RoutingReplayEvaluation(true).runConfiguration();
        var full = new RoutingReplayEvaluation(false).runConfiguration();

        assertAll(
                () -> assertTrue(quick.operationsPerIterationPolicy().contains("latency (mean and percentiles) is per"
                        + " replay iteration")),
                // fresh-state construction is timed; only validation is not
                () -> assertTrue(quick.operationsPerIterationPolicy().contains("only validation of the previous run is"
                        + " untimed")),
                () -> assertTrue(quick.quickMode()),
                () -> assertEquals(1, quick.defaultWarmupIterations()),
                () -> assertEquals(3, quick.defaultMeasurementIterations()),
                () -> assertEquals(3, full.defaultWarmupIterations()),
                () -> assertEquals(10, full.defaultMeasurementIterations()));
    }

    @Test
    void aMultiStageExecutionIsCountedOnceAndPairedByItsClosestStage() {
        var cases = fixtures.cases();
        var tasks = new ArrayList<ReplayTask>();
        for (ReplayTask task : cases.tasks()) {
            RoutingRequest r = task.request();
            if (r.taskId().equals("task-stages") && r.stageKind().equals(SyntheticRoutingFixtures.REVIEW)) {
                // exe-11 routes both implement and review; exe-99 routes review only, under identical keys
                tasks.add(new ReplayTask(withExecution(r, "exe-11", r.requestOrdinal()), task.catalog(),
                        task.oracle()));
                tasks.add(new ReplayTask(withExecution(r, "exe-99", 99), task.catalog(), task.oracle()));
            } else {
                tasks.add(task);
            }
        }
        var runs = runs(cases.withTasks("multi-stage", tasks), ReplayArm.values());
        var pairing = ReplaySummary.of(runs.get(ReplayArm.STATIC)).pairing();

        assertAll(
                () -> assertTrue(RoutingReplayEvaluation.repairedAcceptedCheck(runs).passed(),
                        RoutingReplayEvaluation.repairedAcceptedCheck(runs).detail()),
                // the review stages of exe-11 and exe-99 match, so that pair is directly comparable
                () -> assertEquals(2, pairing.comparable()),
                () -> assertEquals(1, pairing.incomparable()),
                () -> assertEquals(Map.of("context", 1), pairing.incomparableBy()),
                () -> assertEquals(ReplaySummary.firstObservedPerExecution(runs.get(ReplayArm.ADAPTIVE).samples())
                        .size(), ReplaySummary.of(runs.get(ReplayArm.ADAPTIVE)).chain().executions()));
    }

    private static RoutingRequest withExecution(RoutingRequest r, String executionId, long ordinal) {
        return new RoutingRequest(r.contractVersion(), r.scopeId(), r.taskId(), executionId, r.attemptId(),
                r.stageId(), r.stageKind(), ordinal, r.sourceFingerprint(), r.contextFingerprint(),
                r.constraintsFingerprint(), r.evaluationPolicyId(), r.evaluationPolicyVersion(), r.features(),
                r.requirements(), r.overflowPermitted(), r.cutoff());
    }

    private static Map<ReplayArm, ReplayRun> runs(ReplayFixture fixture, ReplayArm... arms) {
        var replay = new TemporalReplay(fixture);
        var runs = new EnumMap<ReplayArm, ReplayRun>(ReplayArm.class);
        for (ReplayArm arm : arms) {
            runs.put(arm, replay.run(arm));
        }
        return runs;
    }
}
