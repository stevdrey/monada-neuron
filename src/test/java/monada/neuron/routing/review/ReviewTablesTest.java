package monada.neuron.routing.review;

import monada.neuron.routing.Basis;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingRule;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static monada.neuron.routing.catalog.EligibilityReason.AVAILABILITY_UNKNOWN;
import static monada.neuron.routing.catalog.EligibilityReason.LOCALITY_NOT_PERMITTED;
import static monada.neuron.routing.catalog.EligibilityReason.MISSING_CAPABILITY;
import static monada.neuron.routing.catalog.EligibilityReason.REQUIRED_LIMIT_EXCEEDED;
import static monada.neuron.routing.catalog.EligibilityReason.REQUIRED_LIMIT_UNKNOWN;
import static monada.neuron.routing.catalog.EligibilityReason.ROUTE_UNAVAILABLE;
import static monada.neuron.routing.catalog.EligibilityReason.STAGE_INCOMPATIBLE;
import static monada.neuron.routing.review.ReviewFixtures.A;
import static monada.neuron.routing.review.ReviewFixtures.B;
import static monada.neuron.routing.review.ReviewFixtures.C;
import static monada.neuron.routing.review.ReviewFixtures.D;
import static monada.neuron.routing.review.ReviewFixtures.cost;
import static monada.neuron.routing.review.ReviewFixtures.costPolicy;
import static monada.neuron.routing.review.ReviewFixtures.decide;
import static monada.neuron.routing.review.ReviewFixtures.entry;
import static monada.neuron.routing.review.ReviewFixtures.exclusion;
import static monada.neuron.routing.review.ReviewFixtures.incompatible;
import static monada.neuron.routing.review.ReviewFixtures.known;
import static monada.neuron.routing.review.ReviewFixtures.noEligibleRoute;
import static monada.neuron.routing.review.ReviewFixtures.request;
import static monada.neuron.routing.review.ReviewFixtures.route;
import static monada.neuron.routing.review.ReviewFixtures.stateIncompatible;
import static monada.neuron.routing.review.ReviewFixtures.tradeoff;
import static monada.neuron.routing.review.ReviewNotice.RECHECK_BEFORE_EXECUTION;
import static monada.neuron.routing.review.ReviewNotice.RESOURCE_OBJECTIVE_NOT_APPLIED;
import static monada.neuron.routing.review.ReviewNotice.STATE_INCOMPATIBLE;
import static monada.neuron.routing.review.ReviewNotice.STATE_NEWER_THAN_CUTOFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ReviewTablesTest {

    static Stream<Arguments> exclusionPrecedence() {
        return Stream.of(
                Arguments.of("mixed primaries group in precedence order",
                        List.of(exclusion(D, ROUTE_UNAVAILABLE), exclusion(A, MISSING_CAPABILITY),
                                exclusion(B, STAGE_INCOMPATIBLE), exclusion(C, LOCALITY_NOT_PERMITTED)),
                        List.of(STAGE_INCOMPATIBLE, ROUTE_UNAVAILABLE, LOCALITY_NOT_PERMITTED, MISSING_CAPABILITY),
                        List.of(List.of(B), List.of(D), List.of(C), List.of(A)),
                        List.of(RECHECK_BEFORE_EXECUTION)),
                Arguments.of("an additional reason never opens its own group, yet raises its notice",
                        List.of(exclusion(A, STAGE_INCOMPATIBLE, AVAILABILITY_UNKNOWN)),
                        List.of(STAGE_INCOMPATIBLE),
                        List.of(List.of(A)),
                        List.of(RECHECK_BEFORE_EXECUTION, ReviewNotice.AVAILABILITY_UNKNOWN)),
                Arguments.of("a shared primary keeps canonical route order",
                        List.of(exclusion(C, ROUTE_UNAVAILABLE), exclusion(A, ROUTE_UNAVAILABLE, REQUIRED_LIMIT_EXCEEDED)),
                        List.of(ROUTE_UNAVAILABLE),
                        List.of(List.of(A, C)),
                        List.of(RECHECK_BEFORE_EXECUTION)),
                Arguments.of("every reason as a primary gives one group per reason",
                        everyReasonOnce(),
                        List.of(EligibilityReason.values()),
                        Stream.of(EligibilityReason.values()).map(reason -> List.of(route(reason.ordinal()))).toList(),
                        List.of(RECHECK_BEFORE_EXECUTION, ReviewNotice.AVAILABILITY_UNKNOWN,
                                ReviewNotice.REQUIRED_LIMIT_UNKNOWN)));
    }

    private static List<EligibilityReport.Exclusion> everyReasonOnce() {
        // Reverse order, so the projection has to restore precedence order itself.
        return Stream.of(EligibilityReason.values()).map(reason -> exclusion(route(reason.ordinal()), reason))
                .toList().reversed();
    }

    // covers: AC-5, AC-7
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void exclusionPrecedence(String name, List<EligibilityReport.Exclusion> exclusions,
            List<EligibilityReason> primaries, List<List<RouteKey>> members, List<ReviewNotice> notices) {
        DecisionReview review = DecisionReview.from(noEligibleRoute(exclusions));

        List<ExclusionGroup> groups = review.exclusionGroups();

        assertEquals(primaries, groups.stream().map(ExclusionGroup::primaryReason).toList());
        assertEquals(members, groups.stream()
                .map(group -> group.exclusions().stream().map(EligibilityReport.Exclusion::key).toList()).toList());
        assertEquals(notices, review.notices());
    }

    static Stream<Arguments> resourceObjective() {
        RouteCatalog tiedOneUnknown = new RouteCatalog("cat-tied", List.of(entry(A, 1, 1), entry(B, 1, 1)),
                List.of(cost(A, known(5)), cost(B, new ResourceValue.Unknown())));
        RouteCatalog sole = new RouteCatalog("cat-sole", List.of(entry(A, 1, 1)), List.of(cost(A, known(5))));
        RouteCatalog tierSeparated = new RouteCatalog("cat-tiers", List.of(entry(A, 1, 1), entry(B, 2, 1)),
                List.of(cost(A, known(9)), cost(B, known(3))));
        RouteCatalog tiedKnown = new RouteCatalog("cat-known", List.of(entry(A, 1, 1), entry(B, 1, 1)),
                List.of(cost(A, known(9)), cost(B, known(3))));
        return Stream.of(
                Arguments.of("configured and skipped among tied routes with an unknown estimate",
                        costPolicy(), tiedOneUnknown, A, true),
                Arguments.of("configured with a sole route", costPolicy(), sole, A, true),
                Arguments.of("configured with routes tier already separated", costPolicy(), tierSeparated, A, true),
                Arguments.of("no objective configured", LexicographicRoutingPolicy.reference(), tiedKnown, A, false),
                Arguments.of("objective applied", costPolicy(), tiedKnown, B, false));
    }

    // covers: AC-6, AC-7
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void resourceObjective(String name, LexicographicRoutingPolicy policy, RouteCatalog catalog, RouteKey winner,
            boolean notApplied) {
        var decision = assertInstanceOf(RoutingDecision.Selected.class, decide(policy, request(List.of()), catalog));
        var review = (DecisionReview.Selected) DecisionReview.from(decision);

        assertEquals(winner, review.route());
        assertEquals(notApplied, review.notices().contains(RESOURCE_OBJECTIVE_NOT_APPLIED));
        if (policy.parameters().objective().isPresent() && !notApplied) {
            assertEquals(Basis.RESOURCE_OBJECTIVE, review.basis());
            assertEquals(Optional.of(RoutingRule.RESOURCE_OBJECTIVE), review.decisiveRule());
        }
    }

    static Stream<Arguments> unknownValuesInExclusions() {
        return Stream.of(
                Arguments.of("required limit unknown as primary", exclusion(C, REQUIRED_LIMIT_UNKNOWN),
                        List.of(RECHECK_BEFORE_EXECUTION, ReviewNotice.REQUIRED_LIMIT_UNKNOWN)),
                Arguments.of("required limit unknown as additional",
                        exclusion(C, MISSING_CAPABILITY, REQUIRED_LIMIT_UNKNOWN),
                        List.of(RECHECK_BEFORE_EXECUTION, ReviewNotice.REQUIRED_LIMIT_UNKNOWN)),
                Arguments.of("availability unknown as primary", exclusion(C, AVAILABILITY_UNKNOWN),
                        List.of(RECHECK_BEFORE_EXECUTION, ReviewNotice.AVAILABILITY_UNKNOWN)),
                Arguments.of("availability unknown as additional",
                        exclusion(C, STAGE_INCOMPATIBLE, AVAILABILITY_UNKNOWN),
                        List.of(RECHECK_BEFORE_EXECUTION, ReviewNotice.AVAILABILITY_UNKNOWN)),
                Arguments.of("both unknowns on one route",
                        exclusion(C, AVAILABILITY_UNKNOWN, MISSING_CAPABILITY, REQUIRED_LIMIT_UNKNOWN),
                        List.of(RECHECK_BEFORE_EXECUTION, ReviewNotice.AVAILABILITY_UNKNOWN,
                                ReviewNotice.REQUIRED_LIMIT_UNKNOWN)),
                Arguments.of("a known limit that is exceeded is not unknown", exclusion(C, REQUIRED_LIMIT_EXCEEDED),
                        List.of(RECHECK_BEFORE_EXECUTION)));
    }

    // covers: AC-6, AC-7
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void unknownValuesInExclusions(String name, EligibilityReport.Exclusion exclusion, List<ReviewNotice> notices) {
        assertEquals(notices, DecisionReview.from(tradeoff(List.of(A, B), List.of(exclusion))).notices());
        assertEquals(notices, DecisionReview.from(noEligibleRoute(List.of(exclusion))).notices());
    }

    static Stream<Arguments> conflictingOrStaleState() {
        return Stream.of(
                Arguments.of("one mismatch", List.of(StateMismatch.SCOPE_MISMATCH),
                        List.of(RECHECK_BEFORE_EXECUTION, STATE_INCOMPATIBLE)),
                Arguments.of("stale only", List.of(StateMismatch.PROCESSED_CUTOFF_NEWER),
                        List.of(RECHECK_BEFORE_EXECUTION, STATE_INCOMPATIBLE, STATE_NEWER_THAN_CUTOFF)),
                Arguments.of("several, not stale",
                        List.of(StateMismatch.FEATURE_SCHEMA_MISMATCH, StateMismatch.EVALUATION_POLICY_MISMATCH),
                        List.of(RECHECK_BEFORE_EXECUTION, STATE_INCOMPATIBLE)),
                Arguments.of("several, stale",
                        List.of(StateMismatch.MAPPING_VERSION_MISMATCH, StateMismatch.PROCESSED_CUTOFF_NEWER),
                        List.of(RECHECK_BEFORE_EXECUTION, STATE_INCOMPATIBLE, STATE_NEWER_THAN_CUTOFF)),
                Arguments.of("every mismatch but stale",
                        List.of(StateMismatch.values()).subList(0, StateMismatch.values().length - 1),
                        List.of(RECHECK_BEFORE_EXECUTION, STATE_INCOMPATIBLE)),
                Arguments.of("every mismatch", List.of(StateMismatch.values()),
                        List.of(RECHECK_BEFORE_EXECUTION, STATE_INCOMPATIBLE, STATE_NEWER_THAN_CUTOFF)));
    }

    // covers: AC-4, AC-6, AC-7
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void conflictingOrStaleState(String name, List<StateMismatch> mismatches, List<ReviewNotice> notices) {
        var decision = stateIncompatible(incompatible(mismatches.toArray(StateMismatch[]::new)), List.of(B, A),
                List.of());

        var review = (DecisionReview.Abstain) DecisionReview.from(decision);

        assertEquals(mismatches, review.stateMismatches());
        assertEquals(List.of(A, B), review.eligible());
        assertEquals(List.of(), review.ranking());
        assertEquals(notices, review.notices());
    }
}
