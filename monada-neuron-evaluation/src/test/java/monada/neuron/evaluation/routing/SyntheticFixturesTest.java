package monada.neuron.evaluation.routing;

import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.outcome.InvocationStatus;
import monada.neuron.routing.outcome.Money;
import monada.neuron.routing.outcome.OutcomeOrigin;
import monada.neuron.routing.outcome.OutcomeStatus;
import monada.neuron.routing.outcome.StageAttribution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyntheticFixturesTest {

    private final SyntheticRoutingFixtures fixtures = new SyntheticRoutingFixtures();

    @Test
    void everyCostIsPricedFromItsTokensAtTheListPrices() {
        var cases = fixtures.cases();
        assertAll(
                // 1200 x 0.015 / 1000 + 300 x 0.080 / 1000, and 1500 x 0.015 / 1000 + 400 x 0.080 / 1000
                () -> assertUsd("0.0420", chainCost(fixtures.degradation(8), 7, SyntheticRoutingFixtures.SUB_A)),
                () -> assertUsd("0.0545", chainCost(fixtures.improvement(8), 5, SyntheticRoutingFixtures.SUB_B)),
                // the routed sub-a attempt plus a 500 / 300 token repair attempt
                () -> assertUsd("0.0735", chainCost(cases, 1, SyntheticRoutingFixtures.SUB_A)),
                // only the 900 known input tokens of a stage whose output is unknown
                () -> assertUsd("0.0135", chainCost(cases, 2, SyntheticRoutingFixtures.SUB_A)));
    }

    @Test
    void anUnknownUsageRepairedChainKeepsTheRepairAttemptInItsLowerBound() {
        // task 142 has unknown usage (142 % 11 == 10) and both routes pass with a repair (142 % 13 == 12)
        ScriptedOutcome outcome = outcomeOf(fixtures.workload(150), 142, SyntheticRoutingFixtures.SUB_A);

        assertAll(
                () -> assertEquals(2, outcome.chain().attempts()),
                () -> assertEquals(ReplayCost.Status.PARTIAL, outcome.chain().cost().status()),
                () -> assertUsd("0.0450", outcome.chain().cost()));
    }

    @Test
    void aCorrectionKeepsTheChainsAttempts() {
        // task 220 is corrected (220 % 17 == 16), and its passing sub-a implement outcome was repaired (220 % 13 == 12)
        ScriptedOutcome outcome = outcomeOf(fixtures.workload(230), 220, SyntheticRoutingFixtures.SUB_A);
        ScriptedOutcome.Correction correction = outcome.correction().orElseThrow();

        assertAll(
                () -> assertEquals(2, outcome.chain().attempts()),
                () -> assertEquals(OutcomeStatus.REJECTED, correction.status()),
                () -> assertEquals(2, correction.chain().attempts()));
    }

    @Test
    void anOracleCopyIgnoresTheCallersComparator() {
        RouteKey v1 = new RouteKey("sub-a", 1);
        RouteKey v2 = new RouteKey("sub-a", 2);
        ScriptedOutcome outcome = outcomeOf(fixtures.improvement(8), 5, SyntheticRoutingFixtures.SUB_B);
        // a comparator that makes every version of a route one key
        var byRouteIdOnly = new TreeMap<RouteKey, ScriptedOutcome>(Comparator.comparing(RouteKey::routeId));
        byRouteIdOnly.put(v2, outcome);

        var oracle = new OutcomeOracle.KnownForEveryRoute(byRouteIdOnly);
        assertAll(
                () -> assertFalse(oracle.outcomeOf(v1).isPresent()),
                () -> assertEquals(outcome, oracle.outcomeOf(v2).orElseThrow()));
    }

    @Test
    void aRepairChainIsNeverAttributedToTheRoutedStage() {
        ScriptedOutcome passing = outcomeOf(fixtures.improvement(8), 5, SyntheticRoutingFixtures.SUB_B);

        assertThrows(IllegalArgumentException.class, () -> new ScriptedOutcome(passing.observedAt(),
                passing.invocation(), passing.status(), passing.origin(), passing.quality(),
                StageAttribution.Basis.STAGE_LOCAL, true, passing.stage(), passing.chain(), passing.correction()));
    }

    @Test
    void aPricedChainNeedsAnAcceptanceThatCanBeValidated() {
        ScriptedOutcome rejected = outcomeOf(fixtures.improvement(8), 5, SyntheticRoutingFixtures.SUB_A);
        ScriptedOutcome passing = outcomeOf(fixtures.improvement(8), 5, SyntheticRoutingFixtures.SUB_B);
        var pricedAcceptance = new ScriptedOutcome.Correction(rejected.observedAt() + 10, OutcomeStatus.ACCEPTED,
                passing.quality(), passing.chain());

        assertAll(
                // an imported claim corrected to a priced acceptance: the correction keeps the imported origin
                () -> assertThrows(IllegalArgumentException.class, () -> new ScriptedOutcome(rejected.observedAt(),
                        rejected.invocation(), rejected.status(), OutcomeOrigin.IMPORTED_CLAIM, rejected.quality(),
                        rejected.attribution(), false, rejected.stage(), rejected.chain(),
                        Optional.of(pricedAcceptance))),
                // a priced acceptance of an invocation that did not complete, with no repair
                () -> assertThrows(IllegalArgumentException.class, () -> new ScriptedOutcome(passing.observedAt(),
                        InvocationStatus.FAILED, passing.status(), passing.origin(), passing.quality(),
                        passing.attribution(), false, passing.stage(), passing.chain(), Optional.empty())),
                () -> assertThrows(IllegalArgumentException.class, () -> new ScriptedOutcome(rejected.observedAt(),
                        InvocationStatus.TIMED_OUT, rejected.status(), rejected.origin(), rejected.quality(),
                        rejected.attribution(), false, rejected.stage(), rejected.chain(),
                        Optional.of(pricedAcceptance))),
                // the validated correction of a completed invocation is priced, as the workload's flips are
                () -> assertEquals(OutcomeStatus.ACCEPTED, rejected.corrected(pricedAcceptance).correction()
                        .orElseThrow().status()),
                // a repair may accept the chain although the routed invocation failed
                () -> assertEquals(ReplayCost.Status.AVAILABLE, new ScriptedOutcome(passing.observedAt(),
                        InvocationStatus.FAILED, passing.status(), passing.origin(), passing.quality(),
                        StageAttribution.Basis.CHAIN_ONLY, true, passing.stage(), passing.chain(), Optional.empty())
                        .chain().cost().status()));
    }

    private static ScriptedOutcome outcomeOf(ReplayFixture fixture, int ordinal, RouteKey route) {
        ReplayTask task = fixture.tasks().stream().filter(t -> t.request().requestOrdinal() == ordinal).findFirst()
                .orElseThrow();
        return ((OutcomeOracle.KnownForEveryRoute) task.oracle()).outcomeOf(route).orElseThrow();
    }

    private static ReplayCost chainCost(ReplayFixture fixture, int ordinal, RouteKey route) {
        return outcomeOf(fixture, ordinal, route).chain().cost();
    }

    private static void assertUsd(String expected, ReplayCost cost) {
        Money usd = cost.amounts().stream().filter(m -> m.currency().equals("USD")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal(expected).compareTo(usd.amount()), () -> "expected " + expected + ", got "
                + usd.amount());
    }
}
