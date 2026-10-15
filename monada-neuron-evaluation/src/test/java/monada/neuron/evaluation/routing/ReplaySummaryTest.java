package monada.neuron.evaluation.routing;

import monada.neuron.routing.outcome.UsageKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySummaryTest {

    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();

    @Test
    void chainFiguresAreHandCalculatedAndCountedOncePerExecution() {
        var summary = ReplaySummary.of(new TemporalReplay(fixtures.cases()).run(ReplayArm.STATIC));
        var chain = summary.chain();

        assertAll(
                // nine executed tasks; the repair adds one attempt
                () -> assertEquals(9, chain.executions()),
                () -> assertEquals(10, chain.attempts()),
                () -> assertEquals(1, chain.rework()),
                // 0.0735 (repair) + 4 x 0.0420 (context and pair tasks) + 0.0420 (two-currency task)
                () -> assertEquals(0, chain.available().get("USD").compareTo(new BigDecimal("0.2835"))),
                () -> assertEquals(0, chain.available().get("EUR").compareTo(new BigDecimal("0.0390"))),
                () -> assertEquals(0, chain.partialLowerBound().get("USD").compareTo(new BigDecimal("0.0270"))),
                // cheap invalid and the corrected security failure have no validated acceptance
                () -> assertEquals(Map.of("AVAILABLE", 6, "PARTIAL", 1, "UNAVAILABLE", 2), chain.costStatus()));
    }

    @Test
    void tokenTalliesAddKnownValuesAndCountGaps() {
        var total = ReplaySummary.of(new TemporalReplay(fixtures.cases()).run(ReplayArm.STATIC)).total();
        var output = total.tokens().get(UsageKind.OUTPUT_TOKENS.name());
        var reasoning = total.tokens().get(UsageKind.REASONING_TOKENS.name());

        assertAll(
                // cheap 50 + seven 300-token sub-a stages; the unknown-usage stage adds nothing to the sum
                () -> assertEquals(50 + 7 * 300, output.knownSum()),
                () -> assertEquals(8, output.known()),
                () -> assertEquals(1, output.unknown()),
                () -> assertEquals(1, reasoning.notMeasured()),
                () -> assertEquals(8, reasoning.absent()));
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
                () -> assertEquals(9, cases.baselineFallback()),
                () -> assertEquals(chosen.chain().executions(),
                        chosen.total().coverage(ReplaySample.Coverage.OBSERVED)),
                () -> assertTrue(chosen.total().coverage(ReplaySample.Coverage.MISSING_COUNTERFACTUAL) > 0));
    }
}
