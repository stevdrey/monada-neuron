package monada.neuron.routing.review;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.Basis;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.Placement;
import monada.neuron.routing.PolicyParameters;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingRule;
import monada.neuron.routing.StateBinding;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.StateValidation;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.RouteKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static monada.neuron.routing.review.ReviewFixtures.A;
import static monada.neuron.routing.review.ReviewFixtures.B;
import static monada.neuron.routing.review.ReviewFixtures.C;
import static monada.neuron.routing.review.ReviewFixtures.COLD_APPLIED;
import static monada.neuron.routing.review.ReviewFixtures.COLD_SKIPPED;
import static monada.neuron.routing.review.ReviewFixtures.D;
import static monada.neuron.routing.review.ReviewFixtures.PARAMETERS;
import static monada.neuron.routing.review.ReviewFixtures.WITH_OBJECTIVE;
import static monada.neuron.routing.review.ReviewFixtures.exclusion;
import static monada.neuron.routing.review.ReviewFixtures.incompatible;
import static monada.neuron.routing.review.ReviewFixtures.noEligibleRoute;
import static monada.neuron.routing.review.ReviewFixtures.provenance;
import static monada.neuron.routing.review.ReviewFixtures.routes;
import static monada.neuron.routing.review.ReviewFixtures.selected;
import static monada.neuron.routing.review.ReviewFixtures.sole;
import static monada.neuron.routing.review.ReviewFixtures.stateIncompatible;
import static monada.neuron.routing.review.ReviewFixtures.tradeoff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionReviewTest {

    private static List<RoutingDecision> oneOfEach() {
        return List.of(selected(List.of(A, B), List.of(exclusion(C, EligibilityReason.ROUTE_UNAVAILABLE))),
                stateIncompatible(incompatible(StateMismatch.SCOPE_MISMATCH), List.of(A), List.of()),
                tradeoff(List.of(A, B), List.of()),
                noEligibleRoute(List.of(exclusion(A, EligibilityReason.STAGE_INCOMPATIBLE))));
    }

    // covers: AC-1
    @Test
    void fromReturnsTheMatchingVariantHoldingTheSameDecision() {
        List<RoutingDecision> decisions = oneOfEach();
        List<Class<? extends DecisionReview>> expected = List.of(DecisionReview.Selected.class,
                DecisionReview.Abstain.class, DecisionReview.Abstain.class, DecisionReview.NoEligibleRoute.class);

        for (int i = 0; i < decisions.size(); i++) {
            DecisionReview review = assertInstanceOf(expected.get(i), DecisionReview.from(decisions.get(i)));
            assertSame(decisions.get(i), review.decision());
        }
    }

    // covers: AC-1
    @Test
    void equalDecisionsGiveEqualReviews() {
        List<RoutingDecision> first = oneOfEach();
        List<RoutingDecision> second = oneOfEach();

        for (int i = 0; i < first.size(); i++) {
            assertNotSame(first.get(i), second.get(i));
            assertEquals(DecisionReview.from(first.get(i)), DecisionReview.from(second.get(i)));
            assertEquals(DecisionReview.from(first.get(i)).hashCode(), DecisionReview.from(second.get(i)).hashCode());
        }
    }

    // covers: AC-1
    @Test
    void nullDecisionIsRejectedByFromAndEveryConstructor() {
        List<Executable> calls = List.of(() -> DecisionReview.from(null),
                () -> new DecisionReview.Selected(null),
                () -> new DecisionReview.Abstain(null),
                () -> new DecisionReview.NoEligibleRoute(null));

        for (Executable call : calls) {
            assertEquals("decision must not be null", assertThrows(NullPointerException.class, call).getMessage());
        }
    }

    // covers: AC-2
    @Test
    void sharedAccessorsEchoTheProvenanceAndTheLibraryContract() {
        for (RoutingDecision decision : oneOfEach()) {
            DecisionReview review = DecisionReview.from(decision);

            assertEquals(decision.provenance().decisionRef(), review.decisionRef());
            assertEquals(decision.provenance().catalogVersion(), review.catalogVersion());
            assertEquals(decision.provenance().policyId(), review.policyId());
            assertEquals(decision.provenance().policyVersion(), review.policyVersion());
            assertEquals(RoutingDecision.CONTRACT_VERSION, review.contractVersion());
        }
    }

    // covers: AC-3
    @Test
    void selectedEchoesTheDecisionAndItsRanking() {
        var decision = selected(provenance(new StateValidation.Compatible()), List.of(B, A, C),
                Placement.FALLBACK_PRIORITY, true, false, COLD_APPLIED, COLD_SKIPPED,
                List.of(exclusion(D, EligibilityReason.MODE_NOT_PERMITTED)));

        var review = (DecisionReview.Selected) DecisionReview.from(decision);

        assertEquals(B, review.route());
        assertEquals("sandboxed", review.executionMode());
        assertEquals(Basis.HOST_PRIORITY, review.basis());
        assertTrue(review.overflowUsed());
        assertEquals(decision.candidates(), review.ranking());
        assertEquals(List.of(B, A, C), review.ranking().stream().map(RankedCandidate::key).toList());
        assertFalse(review.rankingTruncated());
        assertEquals(Optional.of(RoutingRule.FALLBACK_PRIORITY), review.decisiveRule());
        assertThrows(UnsupportedOperationException.class, () -> review.ranking().clear());
    }

    // covers: AC-3
    @Test
    void selectedKeepsATruncatedRankingAsIs() {
        var decision = selected(provenance(new StateValidation.Compatible()), routes(0, 8), Placement.ROUTE_ORDER,
                false, true, COLD_APPLIED, COLD_SKIPPED, List.of());

        var review = (DecisionReview.Selected) DecisionReview.from(decision);

        assertEquals(decision.candidates(), review.ranking());
        assertEquals(RoutingDecision.MAX_CANDIDATES, review.ranking().size());
        assertTrue(review.rankingTruncated());
        assertEquals(Optional.of(RoutingRule.ROUTE_ORDER), review.decisiveRule());
    }

    // covers: AC-3
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Placement.class, mode = EnumSource.Mode.EXCLUDE, names = "ONLY_ELIGIBLE")
    void decisiveRuleIsTheRuleOfTheWinnersPlacement(Placement placement) {
        RoutingRule rule = placement.rule().orElseThrow();
        List<RoutingRule> applied = new ArrayList<>(COLD_APPLIED);
        List<RoutingRule> skipped = new ArrayList<>(COLD_SKIPPED);
        if (skipped.remove(rule)) {
            applied.add(rule);
            applied.sort(null);
        }
        PolicyParameters parameters = rule == RoutingRule.RESOURCE_OBJECTIVE ? WITH_OBJECTIVE : PARAMETERS;
        var decision = selected(provenance(new StateValidation.Compatible(), parameters), List.of(A, B), placement,
                false, false, applied, skipped, List.of());

        var review = (DecisionReview.Selected) DecisionReview.from(decision);

        assertEquals(Optional.of(rule), review.decisiveRule());
        assertEquals(placement.basis(), review.basis());
    }

    // covers: AC-1, AC-5, AC-6
    @Test
    void derivedViewsAreEqualOnEveryCall() {
        for (RoutingDecision decision : oneOfEach()) {
            DecisionReview review = DecisionReview.from(decision);

            assertEquals(review.exclusionGroups(), review.exclusionGroups());
            assertEquals(review.notices(), review.notices());
            assertEquals(review.toString(), DecisionReview.from(decision).toString());
        }
    }

    // covers: AC-3
    @Test
    void soleRouteHasNoDecisiveRule() {
        var review = (DecisionReview.Selected) DecisionReview.from(sole(A, List.of()));

        assertEquals(Optional.empty(), review.decisiveRule());
        assertEquals(Basis.COLD_START, review.basis());
        assertEquals(List.of(new RankedCandidate(0, A, Placement.ONLY_ELIGIBLE)), review.ranking());
    }

    // covers: AC-3
    @Test
    void abstainDeclaresNoDecisiveRule() {
        assertThrows(NoSuchMethodException.class, () -> DecisionReview.Abstain.class.getMethod("decisiveRule"));
    }

    // covers: AC-4
    @Test
    void stateIncompatibleAbstainListsEveryEligibleRouteAndRanksNone() {
        var decision = stateIncompatible(
                incompatible(StateMismatch.SCOPE_MISMATCH, StateMismatch.PROCESSED_CUTOFF_NEWER), List.of(C, A, B),
                List.of(exclusion(D, EligibilityReason.ROUTE_UNAVAILABLE)));

        var review = (DecisionReview.Abstain) DecisionReview.from(decision);

        assertEquals(AbstainReason.STATE_INCOMPATIBLE, review.reason());
        assertEquals(List.of(A, B, C), review.eligible());
        assertEquals(List.of(), review.ranking());
        assertFalse(review.rankingTruncated());
        assertEquals(List.of(StateMismatch.SCOPE_MISMATCH, StateMismatch.PROCESSED_CUTOFF_NEWER),
                review.stateMismatches());
        assertThrows(UnsupportedOperationException.class, () -> review.eligible().clear());
    }

    // covers: AC-4
    @Test
    void tradeoffAbstainRanksLikeItsDecisionWithACompatibleState() {
        var decision = tradeoff(List.of(B, A), List.of());

        var review = (DecisionReview.Abstain) DecisionReview.from(decision);

        assertEquals(AbstainReason.POLICY_TRADEOFF_UNRESOLVED, review.reason());
        assertEquals(List.of(A, B), review.eligible());
        assertEquals(decision.candidates(), review.ranking());
        assertFalse(review.rankingTruncated());
        assertEquals(List.of(), review.stateMismatches());
    }

    // covers: AC-4
    @Test
    void anEligibleRouteBeyondRankEightIsListedButNeverRanked() {
        List<RouteKey> nine = routes(0, 9);
        var decision = tradeoff(nine, List.of());

        var review = (DecisionReview.Abstain) DecisionReview.from(decision);

        assertEquals(nine, review.eligible());
        assertEquals(decision.candidates(), review.ranking());
        assertEquals(RoutingDecision.MAX_CANDIDATES, review.ranking().size());
        assertTrue(review.rankingTruncated());
        assertFalse(review.ranking().stream().map(RankedCandidate::key).toList().contains(nine.getLast()));
    }

    // covers: AC-2
    @Test
    void noAccessorReturnsStateParametersOrCohort() {
        List<String> hidden = List.of(StateBinding.class.getName(), PolicyParameters.class.getName(),
                CohortBinding.class.getName());

        for (Class<?> type : List.of(DecisionReview.class, DecisionReview.Selected.class, DecisionReview.Abstain.class,
                DecisionReview.NoEligibleRoute.class, ExclusionGroup.class)) {
            for (Method method : type.getMethods()) {
                String returned = method.getGenericReturnType().getTypeName();
                for (String name : hidden) {
                    assertFalse(returned.contains(name), type.getSimpleName() + "." + method.getName() + " returns "
                            + returned);
                }
            }
        }
    }
}
