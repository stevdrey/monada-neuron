package monada.neuron.evaluation.routing;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.outcome.Money;
import monada.neuron.routing.outcome.QualityDimension;
import monada.neuron.routing.outcome.ResourceSummary;
import monada.neuron.routing.outcome.UsageCounter;
import monada.neuron.routing.outcome.UsageKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySummaryTest {

    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();

    @Test
    void chainFiguresAreHandCalculatedAndCountedOncePerExecution() {
        var summary = ReplaySummary.of(new TemporalReplay(fixtures.cases()).run(ReplayArm.STATIC));
        var chain = summary.chain();

        assertAll(
                // eleven executed tasks; the repair adds one attempt
                () -> assertEquals(11, chain.executions()),
                () -> assertEquals(12, chain.attempts()),
                () -> assertEquals(1, chain.rework()),
                // 0.0735 (repair) + 4 x 0.0420 (context and pair tasks) + 0.0420 (two-currency task)
                // + 2 x 0.0420 (the implement and review stages of one task, each its own execution)
                () -> assertEquals(0, chain.available().get("USD").compareTo(new BigDecimal("0.3675"))),
                () -> assertEquals(0, chain.available().get("EUR").compareTo(new BigDecimal("0.0390"))),
                () -> assertEquals(0, chain.partialLowerBound().get("USD").compareTo(new BigDecimal("0.0135"))),
                // cheap invalid and the corrected security failure have no validated acceptance
                () -> assertEquals(Map.of("AVAILABLE", 8, "PARTIAL", 1, "UNAVAILABLE", 2), chain.costStatus()));
    }

    @Test
    void twoTasksReusingAnExecutionIdAreTwoExecutions() {
        var base = fixtures.improvement(2);
        var tasks = new ArrayList<ReplayTask>();
        for (ReplayTask task : base.tasks()) {
            RoutingRequest r = task.request();
            // the second task reuses the first task's execution id; execution identity is (scope, task, execution)
            tasks.add(new ReplayTask(new RoutingRequest(r.contractVersion(), r.scopeId(), r.taskId(), "exe-shared",
                    r.attemptId(), r.stageId(), r.stageKind(), r.requestOrdinal(), r.sourceFingerprint(),
                    r.contextFingerprint(), r.constraintsFingerprint(), r.evaluationPolicyId(),
                    r.evaluationPolicyVersion(), r.features(), r.requirements(), r.overflowPermitted(), r.cutoff()),
                    task.catalog(), task.oracle()));
        }
        var fixture = new ReplayFixture(base.fixtureId(), base.version(), base.definition(), base.evidencePolicy(),
                base.policy(), base.partition(), tasks);

        assertEquals(2, ReplaySummary.of(new TemporalReplay(fixture).run(ReplayArm.STATIC)).chain().executions());
    }

    @Test
    void tokenTalliesAddKnownValuesAndCountGaps() {
        var total = ReplaySummary.of(new TemporalReplay(fixtures.cases()).run(ReplayArm.STATIC)).total();
        var output = total.tokens().get(UsageKind.OUTPUT_TOKENS.name());
        var reasoning = total.tokens().get(UsageKind.REASONING_TOKENS.name());

        assertAll(
                // cheap 50 + nine 300-token sub-a stages; the unknown-usage stage adds nothing to the sum
                () -> assertEquals(BigInteger.valueOf(50 + 9 * 300), output.knownSum()),
                () -> assertEquals(10, output.known()),
                () -> assertEquals(1, output.unknown()),
                () -> assertEquals(1, reasoning.notMeasured()),
                () -> assertEquals(10, reasoning.absent()));
    }

    @Test
    void knownTokenTotalsNeverWrap() {
        ReplayRun run = new TemporalReplay(fixtures.cases()).run(ReplayArm.STATIC);
        var huge = List.of(new UsageCounter(UsageKind.OUTPUT_TOKENS,
                new ResourceValue.Known(Long.MAX_VALUE, ResourceValue.Provenance.REPORTED)));
        var samples = run.samples().stream()
                .filter(s -> s.coverage() == ReplaySample.Coverage.OBSERVED).limit(2)
                .map(s -> new ReplaySample(s.decisionRef(), s.stageKind(), s.cutoff(), s.stateCutoff(), s.arm(),
                        s.kind(), s.route(), s.basis(), s.abstainReason(), s.coverage(), s.acceptance(), s.verdict(),
                        s.provenFailures(), s.attempts(), huge, s.usageDeclaration(), s.chainCost(), s.comparison()))
                .toList();
        var summary = ReplaySummary.of(new ReplayRun(ReplayArm.STATIC, samples, List.of(), List.of(),
                run.decisions(), run.derivations(), run.state()));

        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO),
                summary.total().tokens().get(UsageKind.OUTPUT_TOKENS.name()).knownSum());
    }

    @Test
    void chainAttemptTotalsNeverWrap() {
        ReplayRun run = new TemporalReplay(fixtures.cases()).run(ReplayArm.STATIC);
        var samples = run.samples().stream()
                .filter(s -> s.coverage() == ReplaySample.Coverage.OBSERVED).limit(2)
                .map(s -> new ReplaySample(s.decisionRef(), s.stageKind(), s.cutoff(), s.stateCutoff(), s.arm(),
                        s.kind(), s.route(), s.basis(), s.abstainReason(), s.coverage(), s.acceptance(), s.verdict(),
                        s.provenFailures(), Integer.MAX_VALUE, s.stageUsage(), s.usageDeclaration(), s.chainCost(),
                        s.comparison()))
                .toList();
        var chain = ReplaySummary.of(new ReplayRun(ReplayArm.STATIC, samples, List.of(), List.of(), run.decisions(),
                run.derivations(), run.state())).chain();

        assertAll(
                () -> assertEquals(2L * Integer.MAX_VALUE, chain.attempts()),
                () -> assertEquals(2L * (Integer.MAX_VALUE - 1), chain.rework()));
    }

    @Test
    void anExecutionsChainFactsComeFromItsTerminalSample() {
        ReplayRun run = new TemporalReplay(fixtures.cases()).run(ReplayArm.STATIC);
        ReplaySample repaired = run.samples().stream().filter(s -> s.decisionRef().taskId().equals("task-repair"))
                .findFirst().orElseThrow();
        // an earlier routed attempt of the same execution, before its repair was accepted: one attempt, no cost yet
        var ref = repaired.decisionRef();
        var earlier = new ReplaySample(new DecisionRef(ref.scopeId(), ref.taskId(), ref.executionId(), "att-0",
                ref.stageId(), ref.requestOrdinal() - 1), repaired.stageKind(), repaired.cutoff(),
                repaired.stateCutoff(), repaired.arm(), repaired.kind(), repaired.route(), repaired.basis(),
                repaired.abstainReason(), repaired.coverage(), repaired.acceptance(), repaired.verdict(),
                repaired.provenFailures(), 1, repaired.stageUsage(), repaired.usageDeclaration(),
                Optional.of(ReplayCost.unavailable("NO_VALIDATED_ACCEPTANCE")), repaired.comparison());
        var chain = ReplaySummary.of(new ReplayRun(ReplayArm.STATIC, List.of(earlier, repaired), List.of(), List.of(),
                run.decisions(), run.derivations(), run.state())).chain();

        assertAll(
                () -> assertEquals(1, chain.executions()),
                () -> assertEquals(2L, chain.attempts()),
                () -> assertEquals(Map.of("AVAILABLE", 1), chain.costStatus()),
                () -> assertEquals(0, new BigDecimal("0.0735").compareTo(chain.available().get("USD"))));
    }

    @Test
    void aCostWithoutAValidatedAcceptanceIsReportedButNeverSummed() {
        ReplayRun run = new TemporalReplay(fixtures.cases()).run(ReplayArm.STATIC);
        // the cheap invalid task was not accepted, but this copy claims a complete cost to an accepted outcome
        var samples = run.samples().stream().map(s -> !s.decisionRef().taskId().equals("task-cheap") ? s
                : new ReplaySample(s.decisionRef(), s.stageKind(), s.cutoff(), s.stateCutoff(), s.arm(), s.kind(),
                        s.route(), s.basis(), s.abstainReason(), s.coverage(), s.acceptance(), s.verdict(),
                        s.provenFailures(), s.attempts(), s.stageUsage(), s.usageDeclaration(),
                        Optional.of(ReplayCost.available(new Money(new BigDecimal("9.0000"), "USD"))),
                        s.comparison())).toList();
        var inconsistent = new ReplayRun(ReplayArm.STATIC, samples, List.of(), List.of(), run.decisions(),
                run.derivations(), run.state());
        var original = ReplaySummary.of(run).chain();
        var chain = ReplaySummary.of(inconsistent).chain();

        assertAll(
                () -> assertEquals(0, original.costWithoutValidatedAcceptance()),
                () -> assertEquals(1, chain.costWithoutValidatedAcceptance()),
                () -> assertEquals(original.available(), chain.available()),
                () -> assertFalse(RoutingReplayEvaluation.costCompletenessCheck(
                        List.of(Map.of(ReplayArm.STATIC, inconsistent))).passed()),
                // the producer never builds one: a rejected scripted outcome cannot carry a priced chain
                () -> assertThrows(IllegalArgumentException.class, () -> ScriptedOutcome.failing(10,
                        SyntheticRoutingFixtures.GATES, QualityDimension.TESTS, ResourceSummary.unrecorded(),
                        new ChainOutcome(1, ReplayCost.available(new Money(BigDecimal.ONE, "USD"))))));
    }

    @Test
    void twoObservationsOfOneExecutionAreNeverAPair() {
        var base = fixtures.improvement(2);
        var tasks = new ArrayList<ReplayTask>();
        int attempt = 0;
        for (ReplayTask task : base.tasks()) {
            RoutingRequest r = task.request();
            // one task and one execution, routed as two attempts with identical comparison keys
            tasks.add(new ReplayTask(new RoutingRequest(r.contractVersion(), r.scopeId(), "task-one", "exe-one",
                    "att-" + (++attempt), r.stageId(), r.stageKind(), r.requestOrdinal(), r.sourceFingerprint(),
                    r.contextFingerprint(), r.constraintsFingerprint(), r.evaluationPolicyId(),
                    r.evaluationPolicyVersion(), r.features(), r.requirements(), r.overflowPermitted(), r.cutoff()),
                    task.catalog(), task.oracle()));
        }
        var fixture = new ReplayFixture(base.fixtureId(), base.version(), base.definition(), base.evidencePolicy(),
                base.policy(), base.partition(), tasks);
        var pairing = ReplaySummary.of(new TemporalReplay(fixture).run(ReplayArm.STATIC)).pairing();

        assertAll(
                () -> assertEquals(0, pairing.comparable()),
                () -> assertEquals(0, pairing.incomparable()));
    }

    @Test
    void eachPairOfExecutionsIsCountedOnceWhateverItsSampleCount() {
        var base = fixtures.improvement(3);
        var tasks = new ArrayList<ReplayTask>();
        // execution A is routed as two attempts, execution B once; all three share one task and identical keys
        String[][] identities = {{"exe-a", "att-1"}, {"exe-a", "att-2"}, {"exe-b", "att-1"}};
        for (int i = 0; i < base.tasks().size(); i++) {
            ReplayTask task = base.tasks().get(i);
            RoutingRequest r = task.request();
            tasks.add(new ReplayTask(new RoutingRequest(r.contractVersion(), r.scopeId(), "task-one",
                    identities[i][0], identities[i][1], r.stageId(), r.stageKind(), r.requestOrdinal(),
                    r.sourceFingerprint(), r.contextFingerprint(), r.constraintsFingerprint(), r.evaluationPolicyId(),
                    r.evaluationPolicyVersion(), r.features(), r.requirements(), r.overflowPermitted(), r.cutoff()),
                    task.catalog(), task.oracle()));
        }
        var fixture = new ReplayFixture(base.fixtureId(), base.version(), base.definition(), base.evidencePolicy(),
                base.policy(), base.partition(), tasks);
        var pairing = ReplaySummary.of(new TemporalReplay(fixture).run(ReplayArm.STATIC)).pairing();

        // one pair of executions (A, B), not the two sample cross-products A1/B and A2/B
        assertAll(
                () -> assertEquals(1, pairing.comparable()),
                () -> assertEquals(0, pairing.incomparable()));
    }

    @Test
    void stageFiguresNeverCarryChainCost() {
        var summary = ReplaySummary.of(new TemporalReplay(fixtures.workload(120)).run(ReplayArm.ADAPTIVE));

        assertAll(
                () -> assertEquals(Set.of("implement", "qa", "review"), summary.byStage().keySet()),
                () -> assertTrue(summary.lines().keySet().stream().filter(k -> k.contains(".stage."))
                        .noneMatch(k -> k.contains("cost") || k.contains("attempts"))),
                () -> assertEquals(summary.total().decisions(), summary.byStage().values().stream()
                        .mapToInt(ReplaySummary.StageFigures::decisions).sum()));
    }

    @Test
    void abstentionsAndMissingCoverageAreReportedNotScored() {
        var cases = ReplaySummary.of(new TemporalReplay(fixtures.cases()).run(ReplayArm.ADAPTIVE)).total();
        var chosen = ReplaySummary.of(new TemporalReplay(fixtures.chosenRouteOnly(16)).run(ReplayArm.ADAPTIVE));

        assertAll(
                () -> assertEquals(Map.of("POLICY_TRADEOFF_UNRESOLVED", 1), cases.abstained()),
                () -> assertEquals(1, cases.noEligibleRoute()),
                () -> assertEquals(11, cases.baselineFallback()),
                () -> assertEquals(chosen.chain().executions(),
                        chosen.total().coverage(ReplaySample.Coverage.OBSERVED)),
                () -> assertTrue(chosen.total().coverage(ReplaySample.Coverage.MISSING_COUNTERFACTUAL) > 0));
    }
}
