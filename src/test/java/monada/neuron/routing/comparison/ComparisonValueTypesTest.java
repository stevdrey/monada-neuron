package monada.neuron.routing.comparison;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingInput;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RoutingRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static monada.neuron.routing.comparison.ComparisonFixtures.A;
import static monada.neuron.routing.comparison.ComparisonFixtures.B;
import static monada.neuron.routing.comparison.ComparisonFixtures.REFERENCE;
import static monada.neuron.routing.comparison.ComparisonFixtures.catalog;
import static monada.neuron.routing.comparison.ComparisonFixtures.cold;
import static monada.neuron.routing.comparison.ComparisonFixtures.entry;
import static monada.neuron.routing.comparison.ComparisonFixtures.request;
import static monada.neuron.routing.comparison.ComparisonFixtures.route;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComparisonValueTypesTest {

    private final RoutingDecision decision;

    ComparisonValueTypesTest() {
        RoutingRequest built = request().build();
        decision = REFERENCE.decide(built, catalog(), cold(built));
    }

    private static ScenarioDelta unchanged() {
        return new ScenarioDelta(RouteChange.SAME_ROUTE, false, false, false, List.of());
    }

    // covers: AC-3
    @Test
    void everyComparabilityBreakMakesAComparedPairInvalid() {
        for (ScenarioDifference difference : ScenarioDifference.values()) {
            if (difference.breaksComparability()) {
                assertThrows(IllegalArgumentException.class,
                        () -> new ScenarioComparison.Compared(decision, decision, List.of(difference), unchanged()));
                assertEquals(List.of(difference),
                        new ScenarioComparison.Incomparable(decision, decision, List.of(difference)).breaks());
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> new ScenarioComparison.Incomparable(decision, decision, List.of(difference)));
            }
        }
    }

    // covers: AC-3
    @Test
    void exactlyTheSixContractKeysBreakComparability() {
        assertEquals(List.of(ScenarioDifference.SCOPE, ScenarioDifference.TASK, ScenarioDifference.STAGE_KIND,
                ScenarioDifference.FEATURE_SCHEMA, ScenarioDifference.EVALUATION_POLICY, ScenarioDifference.CUTOFF),
                List.of(ScenarioDifference.values()).stream().filter(ScenarioDifference::breaksComparability).toList());
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioComparison.Incomparable(decision, decision, List.of()));
    }

    @Test
    void differencesMustBeUniqueAndInDeclarationOrder() {
        assertThrows(IllegalArgumentException.class, () -> new ScenarioComparison.Compared(decision, decision,
                List.of(ScenarioDifference.PREFERENCE, ScenarioDifference.CATALOG), unchanged()));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioComparison.Compared(decision, decision,
                List.of(ScenarioDifference.CATALOG, ScenarioDifference.CATALOG), unchanged()));
    }

    @Test
    void aDeltaMustAgreeWithTheDecisionsItDescribes() {
        assertThrows(IllegalArgumentException.class, () -> new ScenarioComparison.Compared(decision, decision,
                List.of(), new ScenarioDelta(RouteChange.SAME_ROUTE, true, false, false, List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioDelta(RouteChange.DIFFERENT_ROUTE, false, false, false, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioDelta(RouteChange.SAME_ROUTE, false, true, false, List.of()));
    }

    @Test
    void aComparedDeltaMustMatchTheRouteRankingAndCohortTheDecisionsImply() {
        RoutingRequest built = request().build();
        RoutingDecision other = REFERENCE.decide(built, catalog("cat-b", entry(B, 1, 0)), cold(built));

        assertThrows(IllegalArgumentException.class, () -> new ScenarioComparison.Compared(decision, other,
                List.of(ScenarioDifference.CATALOG), new ScenarioDelta(RouteChange.SAME_ROUTE, true, true, false,
                        List.of())));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioComparison.Compared(decision, other,
                List.of(ScenarioDifference.CATALOG), new ScenarioDelta(RouteChange.DIFFERENT_ROUTE, true, false, false,
                        List.of())));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioComparison.Compared(decision, other,
                List.of(ScenarioDifference.CATALOG), new ScenarioDelta(RouteChange.DIFFERENT_ROUTE, true, true, true,
                        List.of())));
        assertEquals(RouteChange.DIFFERENT_ROUTE, new ScenarioComparison.Compared(decision, other,
                List.of(ScenarioDifference.CATALOG), new ScenarioDelta(RouteChange.DIFFERENT_ROUTE, true, true, false,
                        List.of())).delta().routeChange());
    }

    @Test
    void eligibilityChangesAreRealCanonicalAndBounded() {
        var eligible = new RouteStanding.Eligible();
        var absent = new RouteStanding.NotInCatalog();
        assertThrows(IllegalArgumentException.class, () -> new EligibilityChange(A, eligible, eligible));
        var otherRoute = new RouteStanding.Excluded(
                new EligibilityReport.Exclusion(B, EligibilityReason.ROUTE_UNAVAILABLE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EligibilityChange(A, eligible, otherRoute));

        var a = new EligibilityChange(A, eligible, absent);
        var b = new EligibilityChange(B, eligible, absent);
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioDelta(RouteChange.SAME_ROUTE, true, false, false, List.of(b, a)));

        List<EligibilityChange> tooMany = new ArrayList<>();
        for (int i = 0; i <= ScenarioDelta.MAX_ELIGIBILITY_CHANGES; i++) {
            tooMany.add(new EligibilityChange(route(i), eligible, absent));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ScenarioDelta(RouteChange.SAME_ROUTE, true, false, false, tooMany));
    }

    @Test
    void storedListsAreImmutableCopies() {
        var differences = new ArrayList<>(List.of(ScenarioDifference.CATALOG));
        var comparison = new ScenarioComparison.Compared(decision, decision, differences, unchanged());
        differences.add(ScenarioDifference.PREFERENCE);

        assertEquals(List.of(ScenarioDifference.CATALOG), comparison.differences());
        assertThrows(UnsupportedOperationException.class, () -> comparison.differences().clear());
    }

    @Test
    void scenarioPartsAreRequired() {
        RoutingRequest built = request().build();
        var input = new RoutingInput(built, catalog(), cold(built));
        assertThrows(NullPointerException.class, () -> new RoutingScenario(REFERENCE, null, input));
        assertThrows(NullPointerException.class, () -> new ScenarioComparator().compare(null, null));
    }
}
