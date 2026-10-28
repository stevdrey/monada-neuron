package monada.neuron.routing.comparison;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingInput;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.StateValidation;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.comparison.ComparisonFixtures.Request;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static monada.neuron.routing.comparison.ComparisonFixtures.A;
import static monada.neuron.routing.comparison.ComparisonFixtures.B;
import static monada.neuron.routing.comparison.ComparisonFixtures.C;
import static monada.neuron.routing.comparison.ComparisonFixtures.COST;
import static monada.neuron.routing.comparison.ComparisonFixtures.REFERENCE;
import static monada.neuron.routing.comparison.ComparisonFixtures.TIER_ONE;
import static monada.neuron.routing.comparison.ComparisonFixtures.catalog;
import static monada.neuron.routing.comparison.ComparisonFixtures.cohort;
import static monada.neuron.routing.comparison.ComparisonFixtures.cold;
import static monada.neuron.routing.comparison.ComparisonFixtures.cost;
import static monada.neuron.routing.comparison.ComparisonFixtures.eligible;
import static monada.neuron.routing.comparison.ComparisonFixtures.eligibleOverflow;
import static monada.neuron.routing.comparison.ComparisonFixtures.entry;
import static monada.neuron.routing.comparison.ComparisonFixtures.learned;
import static monada.neuron.routing.comparison.ComparisonFixtures.overflow;
import static monada.neuron.routing.comparison.ComparisonFixtures.request;
import static monada.neuron.routing.comparison.ComparisonFixtures.route;
import static monada.neuron.routing.comparison.ComparisonFixtures.scenario;
import static monada.neuron.routing.comparison.ComparisonFixtures.wide;
import static monada.neuron.routing.comparison.ComparisonFixtures.withCosts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioComparatorTest {

    private final ScenarioComparator comparator = new ScenarioComparator();

    private ScenarioComparison.Compared compared(RoutingScenario before, RoutingScenario after) {
        return assertInstanceOf(ScenarioComparison.Compared.class, comparator.compare(before, after));
    }

    // covers: AC-1, AC-6 (cold state)
    @Test
    void identicalColdScenariosReportNoDifferenceAndLeaveTheInputsEqual() {
        RoutingScenario before = scenario(request(), catalog());
        RoutingScenario after = scenario(request(), catalog());
        RoutingScenario beforeCopy = scenario(request(), catalog());

        var comparison = compared(before, after);

        assertEquals(List.of(), comparison.differences());
        assertEquals(new ScenarioDelta(RouteChange.SAME_ROUTE, false, false, false, List.of()), comparison.delta());
        assertEquals(comparison.before(), comparison.after());
        assertEquals(A, assertInstanceOf(RoutingDecision.Selected.class, comparison.before()).route());
        assertEquals(beforeCopy.input(), before.input());
        assertEquals(List.of(PreviewNotice.HYPOTHETICAL, PreviewNotice.RECHECK_BEFORE_EXECUTION),
                comparison.notices());
    }

    // covers: AC-2 (availability)
    @Test
    void anUnavailableRouteExplainsTheChangedSelection() {
        RoutingScenario before = scenario(request(), catalog());
        RoutingScenario after = scenario(request(), catalog("cat-2",
                entry(A, 1, 0, Availability.UNAVAILABLE), entry(B, 1, 1), entry(C, 1, 2)));

        var comparison = compared(before, after);

        assertEquals(List.of(ScenarioDifference.CATALOG), comparison.differences());
        assertEquals(RouteChange.DIFFERENT_ROUTE, comparison.delta().routeChange());
        assertEquals(B, assertInstanceOf(RoutingDecision.Selected.class, comparison.after()).route());
        assertTrue(comparison.delta().rankingChanged());
        var exclusion = new EligibilityReport.Exclusion(A, EligibilityReason.ROUTE_UNAVAILABLE, List.of());
        assertEquals(List.of(new EligibilityChange(A, eligible(A), new RouteStanding.Excluded(exclusion))),
                comparison.delta().eligibilityChanges());
    }

    // covers: AC-2 (requirement)
    @Test
    void aRequirementNoRouteMeetsExplainsEveryExclusion() {
        Request stricter = request().capabilities(List.of("java", "gpu"), "con-2");
        RoutingScenario before = scenario(request(), catalog());
        RoutingScenario after = scenario(stricter, catalog());

        var comparison = compared(before, after);

        assertEquals(List.of(ScenarioDifference.REQUIREMENTS, ScenarioDifference.REQUEST_CONTEXT),
                comparison.differences());
        assertEquals(RouteChange.NO_LONGER_SELECTED, comparison.delta().routeChange());
        assertInstanceOf(RoutingDecision.NoEligibleRoute.class, comparison.after());
        assertEquals(List.of(A, B, C), comparison.delta().eligibilityChanges().stream()
                .map(EligibilityChange::route).toList());
        for (EligibilityChange change : comparison.delta().eligibilityChanges()) {
            var excluded = assertInstanceOf(RouteStanding.Excluded.class, change.after());
            assertEquals(EligibilityReason.MISSING_CAPABILITY, excluded.exclusion().primaryReason());
        }
    }

    // covers: AC-2 (preference)
    @Test
    void aLearnedPreferenceReordersRoutesOfEqualTierAndPriority() {
        RoutingRequest built = request().build();
        RouteCatalog tied = catalog("cat-t", entry(A, 1, 0), entry(B, 1, 0), entry(C, 1, 0));
        RoutingScenario before = scenario(REFERENCE, new RoutingInput(built, tied, cold(built)));
        RoutingScenario after = scenario(REFERENCE, new RoutingInput(built, tied,
                learned(built, 40L, cohort(A, 0.1, 5), cohort(B, 0.2, 5), cohort(C, 0.9, 5))));

        var comparison = compared(before, after);

        assertEquals(List.of(ScenarioDifference.PREFERENCE), comparison.differences());
        assertEquals(RouteChange.DIFFERENT_ROUTE, comparison.delta().routeChange());
        assertEquals(C, assertInstanceOf(RoutingDecision.Selected.class, comparison.after()).route());
        assertEquals(List.of(), comparison.delta().eligibilityChanges());
        assertFalse(comparison.delta().cohortChanged());
    }

    // covers: AC-2 (stable regardless of entry order)
    @Test
    void permutedCatalogEntriesGiveAnEqualComparison() {
        RoutingScenario after = scenario(request(), catalog("cat-2",
                entry(A, 1, 0, Availability.UNAVAILABLE), entry(B, 1, 1), entry(C, 1, 2)));
        RoutingScenario permuted = scenario(request(), catalog("cat-2",
                entry(C, 1, 2), entry(A, 1, 0, Availability.UNAVAILABLE), entry(B, 1, 1)));

        assertEquals(comparator.compare(scenario(request(), catalog()), after),
                comparator.compare(scenario(request(), catalog()), permuted));
    }

    // covers: AC-2
    @Test
    void routesOnlyOneCatalogListsAreNotInTheOther() {
        RoutingScenario before = scenario(request(), catalog("cat-1", entry(A, 1, 0), entry(C, 1, 2)));
        RoutingScenario after = scenario(request(), catalog("cat-2", entry(B, 1, 1), entry(C, 1, 2)));

        var changes = compared(before, after).delta().eligibilityChanges();

        assertEquals(List.of(
                new EligibilityChange(A, eligible(A), new RouteStanding.NotInCatalog()),
                new EligibilityChange(B, new RouteStanding.NotInCatalog(), eligible(B))), changes);
    }

    // covers: AC-3
    @Test
    void scopeTaskStageEvaluationPolicyAndCutoffChangesAreIncomparable() {
        RouteCatalog catalog = catalog();
        List<Request> others = List.of(request().scope("scope-other"), request().task("task-18"),
                request().stage("review"), request().evaluationPolicy("forge-gates", "2"), request().cutoff(60L));
        List<ScenarioDifference> expected = List.of(ScenarioDifference.SCOPE, ScenarioDifference.TASK,
                ScenarioDifference.STAGE_KIND, ScenarioDifference.EVALUATION_POLICY, ScenarioDifference.CUTOFF);

        for (int i = 0; i < others.size(); i++) {
            var comparison = assertInstanceOf(ScenarioComparison.Incomparable.class,
                    comparator.compare(scenario(request(), catalog), scenario(others.get(i), catalog)));

            assertEquals(List.of(expected.get(i)), comparison.breaks(), others.get(i).toString());
            assertEquals(comparison.before(), comparator.compare(scenario(request(), catalog),
                    scenario(request(), catalog)).before());
            assertEquals(List.of(PreviewNotice.HYPOTHETICAL, PreviewNotice.RECHECK_BEFORE_EXECUTION),
                    comparison.notices());
        }
    }

    // covers: AC-3
    @Test
    void anIncomparablePairStillListsTheComparableDifferences() {
        var comparison = assertInstanceOf(ScenarioComparison.Incomparable.class, comparator.compare(
                scenario(request(), catalog()),
                scenario(request().scope("scope-other"), catalog("cat-2", entry(B, 1, 1)))));

        // The cold snapshot follows the request's scope, so it differs as well.
        assertEquals(List.of(ScenarioDifference.SCOPE, ScenarioDifference.CATALOG, ScenarioDifference.PREFERENCE),
                comparison.differences());
        assertEquals(List.of(ScenarioDifference.SCOPE), comparison.breaks());
    }

    // covers: AC-1
    @Test
    void anotherAttemptOfTheSameStageIsComparable() {
        var comparison = compared(scenario(request(), catalog()), scenario(request().execution("exec-2"), catalog()));

        assertEquals(List.of(ScenarioDifference.REQUEST_IDENTITY), comparison.differences());
        assertEquals(RouteChange.SAME_ROUTE, comparison.delta().routeChange());
        assertTrue(comparison.delta().decisionChanged());
        assertFalse(comparison.delta().rankingChanged());
    }

    // covers: AC-4, AC-6 (stale preference)
    @Test
    void aStalePreferenceKeepsItsStateIncompatibleAbstention() {
        RoutingRequest built = request().build();
        RoutingScenario before = scenario(REFERENCE, new RoutingInput(built, catalog(), cold(built)));
        RoutingScenario after = scenario(REFERENCE, new RoutingInput(built, catalog(), learned(built, 60L)));

        var comparison = compared(before, after);

        var abstain = assertInstanceOf(RoutingDecision.Abstain.class, comparison.after());
        assertEquals(AbstainReason.STATE_INCOMPATIBLE, abstain.reason());
        assertEquals(new StateValidation.Incompatible(List.of(StateMismatch.PROCESSED_CUTOFF_NEWER)),
                abstain.provenance().validation());
        assertEquals(RouteChange.NO_LONGER_SELECTED, comparison.delta().routeChange());
        assertTrue(comparison.delta().rankingChanged());
        assertTrue(comparison.delta().cohortChanged());
        assertEquals(List.of(), comparison.delta().eligibilityChanges());
    }

    // covers: AC-4
    @Test
    void aTierCapKeepsTheTradeoffAbstentionAndItsTruncatedRanking() {
        RouteCatalog catalog = wide("cat-w", 10);
        RoutingRequest built = request().build();
        var input = new RoutingInput(built, catalog, cold(built));
        var tierTwo = new RoutingInput(built, RouteCatalog.of("cat-w2", catalog.entries().stream()
                .map(e -> entry(e.key(), 2, e.fallbackPriority())).toList()), cold(built));

        var comparison = compared(scenario(TIER_ONE, input), scenario(TIER_ONE, tierTwo));

        var selected = assertInstanceOf(RoutingDecision.Selected.class, comparison.before());
        assertTrue(selected.candidatesTruncated());
        var abstain = assertInstanceOf(RoutingDecision.Abstain.class, comparison.after());
        assertEquals(AbstainReason.POLICY_TRADEOFF_UNRESOLVED, abstain.reason());
        assertTrue(abstain.candidatesTruncated());
        assertEquals(10, abstain.eligible().size());
        assertEquals(RouteChange.NO_LONGER_SELECTED, comparison.delta().routeChange());
        assertFalse(comparison.delta().rankingChanged());
    }

    // covers: AC-4
    @Test
    void aRankingThatStopsBeingTruncatedChangesEvenWithEqualVisibleRoutes() {
        RoutingScenario before = scenario(request(), wide("cat-w9", 9));
        RoutingScenario after = scenario(request(), wide("cat-w8", 8));

        var comparison = compared(before, after);

        var was = assertInstanceOf(RoutingDecision.Selected.class, comparison.before());
        var now = assertInstanceOf(RoutingDecision.Selected.class, comparison.after());
        assertTrue(was.candidatesTruncated());
        assertFalse(now.candidatesTruncated());
        assertEquals(was.candidates().stream().map(RankedCandidate::key).toList(),
                now.candidates().stream().map(RankedCandidate::key).toList());
        assertEquals(RouteChange.SAME_ROUTE, comparison.delta().routeChange());
        assertTrue(comparison.delta().rankingChanged());
        assertEquals(List.of(new EligibilityChange(route(8), eligible(route(8)), new RouteStanding.NotInCatalog())),
                comparison.delta().eligibilityChanges());
    }

    // covers: AC-6 (unknown resource), AC-2 (policy parameters)
    @Test
    void anUnknownEstimateLeavesTheCostObjectiveUnapplied() {
        RoutingRequest built = request().build();
        RouteCatalog costs = withCosts("cat-c", catalog().entries(),
                List.of(cost(A, new ResourceValue.Unknown()), cost(B, new ResourceValue.Known(5, ResourceValue.Provenance.REPORTED)),
                        cost(C, new ResourceValue.Known(9, ResourceValue.Provenance.REPORTED))));
        var input = new RoutingInput(built, costs, cold(built));

        var comparison = compared(scenario(REFERENCE, input), scenario(COST, input));

        assertEquals(List.of(ScenarioDifference.POLICY_PARAMETERS), comparison.differences());
        assertEquals(RouteChange.SAME_ROUTE, comparison.delta().routeChange());
        assertEquals(A, assertInstanceOf(RoutingDecision.Selected.class, comparison.after()).route());
        // Only the recorded parameters differ, so the decisions differ while route, ranking and cohort do not.
        assertTrue(comparison.delta().decisionChanged());
        assertFalse(comparison.delta().rankingChanged());
        assertFalse(comparison.delta().cohortChanged());
    }

    // covers: AC-4
    @Test
    void overflowAdmissionChangesStandingWithItsRecordedReason() {
        RouteCatalog catalog = catalog("cat-o", entry(A, 1, 0), overflow(B, 1, 1));
        RoutingScenario before = scenario(request(), catalog);
        RoutingScenario after = scenario(request().overflow(true), catalog);

        var comparison = compared(before, after);

        assertEquals(List.of(ScenarioDifference.OVERFLOW_ADMISSION), comparison.differences());
        var exclusion = new EligibilityReport.Exclusion(B, EligibilityReason.OVERFLOW_NOT_PERMITTED, List.of());
        assertEquals(List.of(exclusion), comparison.before().exclusions());
        assertEquals(List.of(new EligibilityChange(B, new RouteStanding.Excluded(exclusion), eligibleOverflow(B))),
                comparison.delta().eligibilityChanges());
        assertEquals(RouteChange.SAME_ROUTE, comparison.delta().routeChange());
    }

    // covers: AC-2, AC-4
    @Test
    void aRouteThatBecomesOverflowOnlyChangesStandingWhileStayingEligible() {
        RoutingScenario before = scenario(request().overflow(true), catalog("cat-1", entry(A, 1, 0), entry(B, 1, 1)));
        RoutingScenario after = scenario(request().overflow(true), catalog("cat-2", entry(A, 1, 0), overflow(B, 1, 1)));

        var comparison = compared(before, after);

        assertEquals(List.of(ScenarioDifference.CATALOG), comparison.differences());
        assertEquals(List.of(), comparison.after().exclusions());
        assertEquals(List.of(new EligibilityChange(B, eligible(B), eligibleOverflow(B))),
                comparison.delta().eligibilityChanges());
    }

    // covers: AC-2
    @Test
    void relaxingARequirementReportsANewlySelectedRoute() {
        Request stricter = request().capabilities(List.of("java", "gpu"), "con-2");

        var comparison = compared(scenario(stricter, catalog()), scenario(request(), catalog()));

        assertEquals(RouteChange.NEWLY_SELECTED, comparison.delta().routeChange());
        assertInstanceOf(RoutingDecision.NoEligibleRoute.class, comparison.before());
        assertEquals(A, assertInstanceOf(RoutingDecision.Selected.class, comparison.after()).route());
    }

    // covers: AC-2, AC-4
    @Test
    void twoUnselectedSidesListOnlyTheRouteWhoseExclusionChanged() {
        Request stricter = request().capabilities(List.of("java", "gpu"), "con-2");
        RouteCatalog unavailableA = catalog("cat-2",
                entry(A, 1, 0, Availability.UNAVAILABLE), entry(B, 1, 1), entry(C, 1, 2));

        var comparison = compared(scenario(stricter, catalog()), scenario(stricter, unavailableA));

        assertEquals(RouteChange.NOT_SELECTED_EITHER, comparison.delta().routeChange());
        var change = comparison.delta().eligibilityChanges().getFirst();
        assertEquals(1, comparison.delta().eligibilityChanges().size());
        assertEquals(A, change.route());
        assertEquals(EligibilityReason.MISSING_CAPABILITY,
                assertInstanceOf(RouteStanding.Excluded.class, change.before()).exclusion().primaryReason());
        var after = assertInstanceOf(RouteStanding.Excluded.class, change.after()).exclusion();
        assertEquals(EligibilityReason.ROUTE_UNAVAILABLE, after.primaryReason());
        assertEquals(List.of(EligibilityReason.MISSING_CAPABILITY), after.additionalReasons());
    }

    // covers: AC-4
    @Test
    void aChangedExclusionReasonIsAChangeEvenWhenTheRouteStaysExcluded() {
        RouteCatalog unavailable = catalog("cat-2", entry(A, 1, 0, Availability.UNAVAILABLE), entry(B, 1, 1));
        RouteCatalog unknown = catalog("cat-3", entry(A, 1, 0, Availability.UNKNOWN), entry(B, 1, 1));

        var comparison = compared(scenario(request(), unavailable), scenario(request(), unknown));

        assertEquals(RouteChange.SAME_ROUTE, comparison.delta().routeChange());
        var change = comparison.delta().eligibilityChanges().getFirst();
        assertEquals(EligibilityReason.ROUTE_UNAVAILABLE,
                assertInstanceOf(RouteStanding.Excluded.class, change.before()).exclusion().primaryReason());
        assertEquals(EligibilityReason.AVAILABILITY_UNKNOWN,
                assertInstanceOf(RouteStanding.Excluded.class, change.after()).exclusion().primaryReason());
    }

    // covers: AC-2
    @Test
    void anEqualCatalogVersionWithOtherContentStillReportsTheCatalog() {
        RouteCatalog sameVersion = catalog("cat-1",
                entry(A, 1, 0, Availability.UNAVAILABLE), entry(B, 1, 1), entry(C, 1, 2));

        var comparison = compared(scenario(request(), catalog()), scenario(request(), sameVersion));

        assertEquals(List.of(ScenarioDifference.CATALOG), comparison.differences());
        assertEquals(RouteChange.DIFFERENT_ROUTE, comparison.delta().routeChange());
    }

    // covers: AC-2
    @Test
    void twoFullDisjointCatalogsReachTheChangeBoundExactly() {
        List<CatalogEntry> low = new ArrayList<>();
        List<CatalogEntry> high = new ArrayList<>();
        for (int i = 0; i < RouteCatalog.MAX_ROUTES; i++) {
            low.add(entry(route(i), 1, i));
            high.add(entry(route(RouteCatalog.MAX_ROUTES + i), 1, i));
        }

        var comparison = compared(scenario(request(), RouteCatalog.of("cat-low", low)),
                scenario(request(), RouteCatalog.of("cat-high", high)));

        var changes = comparison.delta().eligibilityChanges();
        assertEquals(ScenarioDelta.MAX_ELIGIBILITY_CHANGES, changes.size());
        assertEquals(new EligibilityChange(route(0), eligible(route(0)), new RouteStanding.NotInCatalog()),
                changes.getFirst());
        assertEquals(new EligibilityChange(route(63), new RouteStanding.NotInCatalog(), eligible(route(63))),
                changes.getLast());
    }
}
