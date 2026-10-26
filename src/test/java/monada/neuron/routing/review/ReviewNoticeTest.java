package monada.neuron.routing.review;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.Placement;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingRule;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.StateValidation;
import monada.neuron.routing.catalog.EligibilityReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static monada.neuron.routing.review.ReviewFixtures.A;
import static monada.neuron.routing.review.ReviewFixtures.B;
import static monada.neuron.routing.review.ReviewFixtures.C;
import static monada.neuron.routing.review.ReviewFixtures.COLD_APPLIED;
import static monada.neuron.routing.review.ReviewFixtures.COLD_SKIPPED;
import static monada.neuron.routing.review.ReviewFixtures.exclusion;
import static monada.neuron.routing.review.ReviewFixtures.incompatible;
import static monada.neuron.routing.review.ReviewFixtures.noEligibleRoute;
import static monada.neuron.routing.review.ReviewFixtures.provenance;
import static monada.neuron.routing.review.ReviewFixtures.provenanceWithoutStageKind;
import static monada.neuron.routing.review.ReviewFixtures.routes;
import static monada.neuron.routing.review.ReviewFixtures.selected;
import static monada.neuron.routing.review.ReviewFixtures.stateIncompatible;
import static monada.neuron.routing.review.ReviewFixtures.tradeoff;
import static monada.neuron.routing.review.ReviewNotice.LEARNED_PREFERENCE_NOT_APPLIED;
import static monada.neuron.routing.review.ReviewNotice.OVERFLOW_ROUTE_SELECTED;
import static monada.neuron.routing.review.ReviewNotice.RANKING_TRUNCATED;
import static monada.neuron.routing.review.ReviewNotice.RECHECK_BEFORE_EXECUTION;
import static monada.neuron.routing.review.ReviewNotice.STAGE_KIND_NOT_RECORDED;
import static monada.neuron.routing.review.ReviewNotice.STATE_INCOMPATIBLE;
import static monada.neuron.routing.review.ReviewNotice.STATE_NEWER_THAN_CUTOFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewNoticeTest {

    private static List<ReviewNotice> notices(RoutingDecision decision) {
        return DecisionReview.from(decision).notices();
    }

    private static RoutingDecision.Selected learnedSelection() {
        return selected(provenance(new StateValidation.Compatible()), List.of(A, B), Placement.LEARNED_PREFERENCE,
                false, false, List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.LEARNED_PREFERENCE,
                        RoutingRule.ROUTE_ORDER), List.of(RoutingRule.RESOURCE_OBJECTIVE), List.of());
    }

    // covers: AC-6
    @Test
    void declarationOrderIsTheContract() {
        assertEquals(List.of("RECHECK_BEFORE_EXECUTION", "OVERFLOW_ROUTE_SELECTED", "RANKING_TRUNCATED",
                "STATE_INCOMPATIBLE", "STATE_NEWER_THAN_CUTOFF", "AVAILABILITY_UNKNOWN", "REQUIRED_LIMIT_UNKNOWN",
                "LEARNED_PREFERENCE_NOT_APPLIED", "RESOURCE_OBJECTIVE_NOT_APPLIED", "STAGE_KIND_NOT_RECORDED"),
                List.of(ReviewNotice.values()).stream().map(Enum::name).toList());
    }

    // covers: AC-6
    @Test
    void everyVariantCarriesOnlyTheRecheckNoticeWhenNothingElseIsRecorded() {
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION), notices(learnedSelection()));
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION), notices(tradeoff(List.of(A, B), List.of())));
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION),
                notices(noEligibleRoute(List.of(exclusion(A, EligibilityReason.ROUTE_UNAVAILABLE)))));
    }

    // covers: AC-6
    @Test
    void noticesAreUniqueInDeclarationOrderAndUnmodifiable() {
        var decision = selected(provenanceWithoutStageKind(new StateValidation.Compatible()), routes(0, 8),
                Placement.ROUTE_ORDER, true, true, COLD_APPLIED, COLD_SKIPPED,
                List.of(exclusion(routes(8, 1).getFirst(), EligibilityReason.AVAILABILITY_UNKNOWN,
                                EligibilityReason.REQUIRED_LIMIT_UNKNOWN),
                        exclusion(routes(9, 1).getFirst(), EligibilityReason.AVAILABILITY_UNKNOWN)));

        List<ReviewNotice> notices = notices(decision);

        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, OVERFLOW_ROUTE_SELECTED, RANKING_TRUNCATED,
                ReviewNotice.AVAILABILITY_UNKNOWN, ReviewNotice.REQUIRED_LIMIT_UNKNOWN, LEARNED_PREFERENCE_NOT_APPLIED,
                STAGE_KIND_NOT_RECORDED), notices);
        assertThrows(UnsupportedOperationException.class, () -> notices.add(RECHECK_BEFORE_EXECUTION));
    }

    // covers: AC-6
    @Test
    void overflowNoticeEchoesTheSelection() {
        var overflow = selected(provenance(new StateValidation.Compatible()), List.of(A, B), Placement.TIER, true,
                false, COLD_APPLIED, COLD_SKIPPED, List.of());

        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, OVERFLOW_ROUTE_SELECTED, LEARNED_PREFERENCE_NOT_APPLIED),
                notices(overflow));
    }

    // covers: AC-6
    @Test
    void truncationNoticeFollowsTheDecisionFlag() {
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, RANKING_TRUNCATED), notices(tradeoff(routes(0, 9), List.of())));
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION), notices(tradeoff(routes(0, 8), List.of())));
    }

    // covers: AC-6
    @Test
    void stateNoticesComeOnlyFromAnIncompatibleValidation() {
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, STATE_INCOMPATIBLE),
                notices(stateIncompatible(incompatible(StateMismatch.MAPPING_VERSION_MISMATCH), List.of(A),
                        List.of())));
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, STATE_INCOMPATIBLE, STATE_NEWER_THAN_CUTOFF),
                notices(stateIncompatible(incompatible(StateMismatch.PROCESSED_CUTOFF_NEWER), List.of(A),
                        List.of())));
        // NotEvaluated means the state was never consulted, which is not a state problem.
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION),
                notices(noEligibleRoute(List.of(exclusion(A, EligibilityReason.MISSING_CAPABILITY)))));
    }

    // covers: AC-6
    @Test
    void learnedPreferenceNoticeOnlyOnASelectionThatSkippedIt() {
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, LEARNED_PREFERENCE_NOT_APPLIED),
                notices(selected(List.of(A, B), List.of())));
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION), notices(learnedSelection()));
        // An abstention does not record which rules ran, so the review claims nothing about them.
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION), notices(tradeoff(List.of(A, B), List.of())));
    }

    // covers: AC-6
    @Test
    void anAbstentionUnderAnObjectiveClaimsNothingAboutTheObjective() {
        var abstention = new RoutingDecision.Abstain(
                provenance(new StateValidation.Compatible(), ReviewFixtures.WITH_OBJECTIVE),
                AbstainReason.POLICY_TRADEOFF_UNRESOLVED, List.of(A, B),
                ReviewFixtures.ranked(List.of(A, B), Placement.ROUTE_ORDER), false, List.of(),
                Optional.of(ReviewFixtures.COHORT));

        assertEquals(List.of(RECHECK_BEFORE_EXECUTION), notices(abstention));
    }

    // covers: AC-6
    @Test
    void stageKindNoticeOnEveryVariantThatDidNotRecordIt() {
        var selection = selected(provenanceWithoutStageKind(new StateValidation.Compatible()), List.of(A, B),
                Placement.LEARNED_PREFERENCE, false, false, List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY,
                        RoutingRule.LEARNED_PREFERENCE, RoutingRule.ROUTE_ORDER),
                List.of(RoutingRule.RESOURCE_OBJECTIVE), List.of());
        var abstention = new RoutingDecision.Abstain(
                provenanceWithoutStageKind(incompatible(StateMismatch.SCOPE_MISMATCH)),
                AbstainReason.STATE_INCOMPATIBLE, List.of(A), List.of(), false, List.of());
        var none = new RoutingDecision.NoEligibleRoute(provenanceWithoutStageKind(new StateValidation.NotEvaluated()),
                List.of(exclusion(C, EligibilityReason.ROUTE_UNAVAILABLE)));

        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, STAGE_KIND_NOT_RECORDED), notices(selection));
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, STATE_INCOMPATIBLE, STAGE_KIND_NOT_RECORDED),
                notices(abstention));
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, STAGE_KIND_NOT_RECORDED), notices(none));
    }
}
