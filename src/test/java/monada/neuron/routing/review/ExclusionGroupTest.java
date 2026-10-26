package monada.neuron.routing.review;

import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static monada.neuron.routing.catalog.EligibilityReason.AVAILABILITY_UNKNOWN;
import static monada.neuron.routing.catalog.EligibilityReason.MISSING_CAPABILITY;
import static monada.neuron.routing.catalog.EligibilityReason.REQUIRED_LIMIT_UNKNOWN;
import static monada.neuron.routing.catalog.EligibilityReason.ROUTE_UNAVAILABLE;
import static monada.neuron.routing.catalog.EligibilityReason.STAGE_INCOMPATIBLE;
import static monada.neuron.routing.review.ReviewFixtures.A;
import static monada.neuron.routing.review.ReviewFixtures.B;
import static monada.neuron.routing.review.ReviewFixtures.C;
import static monada.neuron.routing.review.ReviewFixtures.D;
import static monada.neuron.routing.review.ReviewFixtures.exclusion;
import static monada.neuron.routing.review.ReviewFixtures.noEligibleRoute;
import static monada.neuron.routing.review.ReviewFixtures.route;
import static monada.neuron.routing.review.ReviewFixtures.selected;
import static monada.neuron.routing.review.ReviewFixtures.tradeoff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusionGroupTest {

    // covers: AC-5
    @Test
    void groupsFollowPrecedenceOrderAndHoldEveryExclusionOnce() {
        var unavailableD = exclusion(D, ROUTE_UNAVAILABLE);
        var missingA = exclusion(A, MISSING_CAPABILITY, REQUIRED_LIMIT_UNKNOWN);
        var stageC = exclusion(C, STAGE_INCOMPATIBLE, AVAILABILITY_UNKNOWN, MISSING_CAPABILITY);
        var unavailableB = exclusion(B, ROUTE_UNAVAILABLE);
        RoutingDecision decision = noEligibleRoute(List.of(unavailableD, missingA, stageC, unavailableB));

        List<ExclusionGroup> groups = DecisionReview.from(decision).exclusionGroups();

        assertEquals(List.of(new ExclusionGroup(STAGE_INCOMPATIBLE, List.of(stageC)),
                new ExclusionGroup(ROUTE_UNAVAILABLE, List.of(unavailableB, unavailableD)),
                new ExclusionGroup(MISSING_CAPABILITY, List.of(missingA))), groups);
        assertEquals(List.of(AVAILABILITY_UNKNOWN, MISSING_CAPABILITY), groups.getFirst().exclusions().getFirst()
                .additionalReasons());
        List<EligibilityReport.Exclusion> flattened = new ArrayList<>();
        groups.forEach(group -> flattened.addAll(group.exclusions()));
        assertEquals(decision.exclusions().size(), flattened.size());
        assertTrue(flattened.containsAll(decision.exclusions()));
        assertThrows(UnsupportedOperationException.class, () -> groups.add(groups.getFirst()));
    }

    // covers: AC-5
    @Test
    void noExclusionsGiveNoGroups() {
        DecisionReview review = DecisionReview.from(selected(List.of(A, B), List.of()));

        assertEquals(List.of(), review.exclusionGroups());
        for (EligibilityReason reason : EligibilityReason.values()) {
            assertEquals(0, review.excludedCount(reason));
        }
    }

    // covers: AC-5
    @Test
    void excludedCountNamesPrimaryAndAdditionalReasons() {
        DecisionReview review = DecisionReview.from(noEligibleRoute(List.of(
                exclusion(A, STAGE_INCOMPATIBLE, AVAILABILITY_UNKNOWN),
                exclusion(B, AVAILABILITY_UNKNOWN),
                exclusion(C, ROUTE_UNAVAILABLE, REQUIRED_LIMIT_UNKNOWN))));

        assertEquals(2, review.excludedCount(AVAILABILITY_UNKNOWN));
        assertEquals(1, review.excludedCount(STAGE_INCOMPATIBLE));
        assertEquals(1, review.excludedCount(REQUIRED_LIMIT_UNKNOWN));
        assertEquals(0, review.excludedCount(MISSING_CAPABILITY));
        assertEquals("reason must not be null",
                assertThrows(NullPointerException.class, () -> review.excludedCount(null)).getMessage());
    }

    // covers: AC-5
    @Test
    void constructorStoresCanonicalRouteOrderAsAnUnmodifiableCopy() {
        var later = exclusion(C, ROUTE_UNAVAILABLE);
        var earlier = exclusion(A, ROUTE_UNAVAILABLE, MISSING_CAPABILITY);
        List<EligibilityReport.Exclusion> given = new ArrayList<>(List.of(later, earlier));

        ExclusionGroup group = new ExclusionGroup(ROUTE_UNAVAILABLE, given);
        given.clear();

        assertEquals(List.of(earlier, later), group.exclusions());
        assertThrows(UnsupportedOperationException.class, () -> group.exclusions().clear());
    }

    // covers: AC-5
    @Test
    void constructorRejectsGroupsNoDecisionCouldProduce() {
        List<EligibilityReport.Exclusion> tooMany = new ArrayList<>();
        for (int i = 0; i <= RouteCatalog.MAX_ROUTES; i++) {
            tooMany.add(exclusion(route(i), ROUTE_UNAVAILABLE));
        }

        assertThrows(IllegalArgumentException.class, () -> new ExclusionGroup(ROUTE_UNAVAILABLE, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExclusionGroup(ROUTE_UNAVAILABLE, List.of(exclusion(A, STAGE_INCOMPATIBLE))));
        assertThrows(IllegalArgumentException.class, () -> new ExclusionGroup(ROUTE_UNAVAILABLE,
                List.of(exclusion(A, ROUTE_UNAVAILABLE), exclusion(A, ROUTE_UNAVAILABLE, MISSING_CAPABILITY))));
        assertThrows(IllegalArgumentException.class, () -> new ExclusionGroup(ROUTE_UNAVAILABLE, tooMany));
    }

    // covers: AC-5
    @Test
    void constructorAcceptsAFullCatalogInOneGroup() {
        List<EligibilityReport.Exclusion> full = new ArrayList<>();
        for (int i = RouteCatalog.MAX_ROUTES - 1; i >= 0; i--) {
            full.add(exclusion(route(i), ROUTE_UNAVAILABLE));
        }

        List<ExclusionGroup> groups = DecisionReview.from(noEligibleRoute(full)).exclusionGroups();

        assertEquals(1, groups.size());
        assertEquals(RouteCatalog.MAX_ROUTES, groups.getFirst().exclusions().size());
        assertEquals(route(0), groups.getFirst().exclusions().getFirst().key());
        assertEquals(RouteCatalog.MAX_ROUTES, DecisionReview.from(noEligibleRoute(full)).excludedCount(ROUTE_UNAVAILABLE));
    }

    // covers: AC-5
    @Test
    void equalMembersInAnyOrderGiveEqualGroups() {
        var first = exclusion(A, ROUTE_UNAVAILABLE);
        var second = exclusion(B, ROUTE_UNAVAILABLE, MISSING_CAPABILITY);

        assertEquals(new ExclusionGroup(ROUTE_UNAVAILABLE, List.of(first, second)),
                new ExclusionGroup(ROUTE_UNAVAILABLE, List.of(second, first)));
    }

    // covers: AC-5
    @Test
    void groupsAndCountsWorkTheSameOnSelectionsAndAbstentions() {
        var stage = exclusion(C, STAGE_INCOMPATIBLE, AVAILABILITY_UNKNOWN);
        var unavailable = exclusion(D, ROUTE_UNAVAILABLE);

        for (RoutingDecision decision : List.of(selected(List.of(A, B), List.of(stage, unavailable)),
                tradeoff(List.of(A, B), List.of(unavailable, stage)))) {
            DecisionReview review = DecisionReview.from(decision);

            assertEquals(List.of(new ExclusionGroup(STAGE_INCOMPATIBLE, List.of(stage)),
                    new ExclusionGroup(ROUTE_UNAVAILABLE, List.of(unavailable))), review.exclusionGroups());
            assertEquals(1, review.excludedCount(AVAILABILITY_UNKNOWN));
        }
    }

    // covers: AC-5, AC-6
    @Test
    void groupingInvariantsHoldForGeneratedDecisions() {
        // A fixed seed keeps the run deterministic while covering many shapes no hand written case would pick.
        Random random = new Random(96L);
        EligibilityReason[] reasons = EligibilityReason.values();

        for (int run = 0; run < 500; run++) {
            int size = 1 + random.nextInt(RouteCatalog.MAX_ROUTES);
            List<EligibilityReport.Exclusion> exclusions = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                EligibilityReason primary = reasons[random.nextInt(reasons.length)];
                List<EligibilityReason> additional = new ArrayList<>();
                for (int r = primary.ordinal() + 1; r < reasons.length; r++) {
                    if (random.nextBoolean()) {
                        additional.add(reasons[r]);
                    }
                }
                exclusions.add(new EligibilityReport.Exclusion(route(i), primary, additional));
            }
            Collections.shuffle(exclusions, random);
            RoutingDecision decision = noEligibleRoute(exclusions);

            DecisionReview review = DecisionReview.from(decision);

            List<ExclusionGroup> groups = review.exclusionGroups();
            List<EligibilityReport.Exclusion> flattened = new ArrayList<>();
            for (int g = 0; g < groups.size(); g++) {
                ExclusionGroup group = groups.get(g);
                if (g > 0) {
                    assertTrue(groups.get(g - 1).primaryReason().compareTo(group.primaryReason()) < 0, "run " + run);
                }
                for (int m = 1; m < group.exclusions().size(); m++) {
                    assertTrue(group.exclusions().get(m - 1).key().compareTo(group.exclusions().get(m).key()) < 0,
                            "run " + run);
                }
                flattened.addAll(group.exclusions());
            }
            assertEquals(decision.exclusions().size(), flattened.size(), "run " + run);
            assertEquals(Set.copyOf(decision.exclusions()), Set.copyOf(flattened), "run " + run);
            for (EligibilityReason reason : reasons) {
                long expected = decision.exclusions().stream()
                        .filter(e -> e.primaryReason() == reason || e.additionalReasons().contains(reason)).count();
                assertEquals(expected, review.excludedCount(reason), "run " + run + " " + reason);
            }
            assertEquals(review.excludedCount(AVAILABILITY_UNKNOWN) > 0,
                    review.notices().contains(ReviewNotice.AVAILABILITY_UNKNOWN), "run " + run);
            assertEquals(review.excludedCount(REQUIRED_LIMIT_UNKNOWN) > 0,
                    review.notices().contains(ReviewNotice.REQUIRED_LIMIT_UNKNOWN), "run " + run);
        }
    }

    // covers: AC-5
    @Test
    void constructorRejectsNulls() {
        List<EligibilityReport.Exclusion> withNull = Arrays.asList(exclusion(A, ROUTE_UNAVAILABLE), null);

        assertEquals("primaryReason must not be null", assertThrows(NullPointerException.class,
                () -> new ExclusionGroup(null, List.of(exclusion(A, ROUTE_UNAVAILABLE)))).getMessage());
        assertEquals("exclusions must not be null",
                assertThrows(NullPointerException.class, () -> new ExclusionGroup(ROUTE_UNAVAILABLE, null))
                        .getMessage());
        assertEquals("exclusion must not be null",
                assertThrows(NullPointerException.class, () -> new ExclusionGroup(ROUTE_UNAVAILABLE, withNull))
                        .getMessage());
    }
}
