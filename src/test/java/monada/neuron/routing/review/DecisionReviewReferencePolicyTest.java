package monada.neuron.routing.review;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static monada.neuron.routing.review.ReviewFixtures.A;
import static monada.neuron.routing.review.ReviewFixtures.B;
import static monada.neuron.routing.review.ReviewFixtures.C;
import static monada.neuron.routing.review.ReviewFixtures.decide;
import static monada.neuron.routing.review.ReviewFixtures.entry;
import static monada.neuron.routing.review.ReviewFixtures.request;
import static monada.neuron.routing.review.ReviewFixtures.route;
import static monada.neuron.routing.review.ReviewNotice.AVAILABILITY_UNKNOWN;
import static monada.neuron.routing.review.ReviewNotice.LEARNED_PREFERENCE_NOT_APPLIED;
import static monada.neuron.routing.review.ReviewNotice.RANKING_TRUNCATED;
import static monada.neuron.routing.review.ReviewNotice.RECHECK_BEFORE_EXECUTION;
import static monada.neuron.routing.review.ReviewNotice.STATE_INCOMPATIBLE;
import static monada.neuron.routing.review.ReviewNotice.STATE_NEWER_THAN_CUTOFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionReviewReferencePolicyTest {

    private static final LexicographicRoutingPolicy POLICY = LexicographicRoutingPolicy.reference();

    private static CatalogEntry withAvailability(CatalogEntry entry, Availability availability) {
        return new CatalogEntry(entry.descriptor(), availability, entry.fallbackPriority());
    }

    /** Two tied available routes and one unavailable route. */
    private static RouteCatalog twoEligible() {
        return RouteCatalog.of("cat-two", List.of(entry(A, 1, 1), entry(B, 1, 1),
                withAvailability(entry(C, 1, 1), Availability.UNAVAILABLE)));
    }

    // covers: AC-8
    @Test
    void selectionMatchesItsDecisionFieldForField() {
        RoutingDecision decision = decide(POLICY, request(List.of()), twoEligible());
        RoutingDecision copy = decide(POLICY, request(List.of()), twoEligible());
        var selected = assertInstanceOf(RoutingDecision.Selected.class, decision);

        var review = assertInstanceOf(DecisionReview.Selected.class, DecisionReview.from(decision));

        assertShared(decision, review);
        assertEquals(selected.route(), review.route());
        assertEquals(selected.executionMode(), review.executionMode());
        assertEquals(selected.basis(), review.basis());
        assertEquals(selected.overflowUsed(), review.overflowUsed());
        assertEquals(selected.candidates(), review.ranking());
        assertEquals(2, review.ranking().size());
        assertEquals(selected.candidatesTruncated(), review.rankingTruncated());
        assertEquals(selected.candidates().getFirst().placement().rule(), review.decisiveRule());
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, LEARNED_PREFERENCE_NOT_APPLIED), review.notices());
        assertEquals(copy, decision);
    }

    // covers: AC-8
    @Test
    void noEligibleRouteMatchesItsDecisionFieldForField() {
        RouteCatalog catalog = RouteCatalog.of("cat-none", List.of(entry(A, 1, 1), entry(B, 1, 1),
                withAvailability(entry(C, 1, 1), Availability.UNKNOWN)));
        RoutingDecision decision = decide(POLICY, request(List.of("rust")), catalog);
        RoutingDecision copy = decide(POLICY, request(List.of("rust")), catalog);
        assertInstanceOf(RoutingDecision.NoEligibleRoute.class, decision);

        var review = assertInstanceOf(DecisionReview.NoEligibleRoute.class, DecisionReview.from(decision));

        assertShared(decision, review);
        assertEquals(List.of(EligibilityReason.AVAILABILITY_UNKNOWN, EligibilityReason.MISSING_CAPABILITY),
                review.exclusionGroups().stream().map(ExclusionGroup::primaryReason).toList());
        assertEquals(List.of(EligibilityReason.MISSING_CAPABILITY),
                review.exclusionGroups().getFirst().exclusions().getFirst().additionalReasons());
        assertEquals(3, review.excludedCount(EligibilityReason.MISSING_CAPABILITY));
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, AVAILABILITY_UNKNOWN), review.notices());
        assertEquals(copy, decision);
    }

    // covers: AC-8
    @Test
    void stateIncompatibleAbstentionMatchesItsDecisionFieldForField() {
        RoutingRequest request = request(List.of());
        // A watermark past the request cutoff fails admission while both routes stay eligible.
        var stale = new RoutingPreference(request.scopeId(), request.features().schemaVersion(),
                request.evaluationPolicyId(), request.evaluationPolicyVersion(),
                POLICY.definition().mappingVersion(), POLICY.policyId(), POLICY.policyVersion(),
                request.cutoff() + 1, List.of());
        RoutingDecision decision = POLICY.decide(request, twoEligible(), stale);
        RoutingDecision copy = POLICY.decide(request, twoEligible(), stale);
        var abstain = assertInstanceOf(RoutingDecision.Abstain.class, decision);

        var review = assertInstanceOf(DecisionReview.Abstain.class, DecisionReview.from(decision));

        assertShared(decision, review);
        assertEquals(AbstainReason.STATE_INCOMPATIBLE, review.reason());
        assertEquals(abstain.reason(), review.reason());
        assertEquals(abstain.eligible(), review.eligible());
        assertEquals(List.of(A, B), review.eligible());
        assertEquals(abstain.candidates(), review.ranking());
        assertEquals(List.of(), review.ranking());
        assertEquals(abstain.candidatesTruncated(), review.rankingTruncated());
        assertEquals(List.of(StateMismatch.PROCESSED_CUTOFF_NEWER), review.stateMismatches());
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, STATE_INCOMPATIBLE, STATE_NEWER_THAN_CUTOFF), review.notices());
        assertEquals(copy, decision);
    }

    /** Nine available routes tied on tier and priority, so route order ranks them and the ninth is cut. */
    private static RouteCatalog nineTied(int tier) {
        List<CatalogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            entries.add(entry(route(i), tier, 1));
        }
        return RouteCatalog.of("cat-nine", entries);
    }

    // covers: AC-3, AC-6
    @Test
    void aRealSelectionOverNineRoutesKeepsTheDecisionsTruncatedRanking() {
        RoutingDecision decision = decide(POLICY, request(List.of()), nineTied(1));
        var selected = assertInstanceOf(RoutingDecision.Selected.class, decision);

        var review = assertInstanceOf(DecisionReview.Selected.class, DecisionReview.from(decision));

        assertEquals(selected.candidates(), review.ranking());
        assertEquals(RoutingDecision.MAX_CANDIDATES, review.ranking().size());
        assertTrue(review.rankingTruncated());
        assertFalse(review.ranking().stream().map(RankedCandidate::key).toList().contains(route(8)));
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, RANKING_TRUNCATED, LEARNED_PREFERENCE_NOT_APPLIED),
                review.notices());
    }

    // covers: AC-4, AC-6
    @Test
    void aRealTierCappedAbstentionKeepsItsRankingAndEveryEligibleRoute() {
        var capped = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(),
                LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.of(1), Optional.empty());
        RoutingDecision decision = decide(capped, request(List.of()), nineTied(2));
        var abstain = assertInstanceOf(RoutingDecision.Abstain.class, decision);

        var review = assertInstanceOf(DecisionReview.Abstain.class, DecisionReview.from(decision));

        assertSame(decision, review.decision());
        assertEquals(AbstainReason.POLICY_TRADEOFF_UNRESOLVED, review.reason());
        assertEquals(abstain.candidates(), review.ranking());
        assertEquals(RoutingDecision.MAX_CANDIDATES, review.ranking().size());
        assertEquals(9, review.eligible().size());
        assertTrue(review.eligible().contains(route(8)));
        assertEquals(List.of(), review.stateMismatches());
        assertEquals(List.of(RECHECK_BEFORE_EXECUTION, RANKING_TRUNCATED), review.notices());
    }

    private static void assertShared(RoutingDecision decision, DecisionReview review) {
        assertSame(decision, review.decision());
        assertEquals(decision.provenance().decisionRef(), review.decisionRef());
        assertEquals(decision.provenance().catalogVersion(), review.catalogVersion());
        assertEquals(decision.provenance().policyId(), review.policyId());
        assertEquals(decision.provenance().policyVersion(), review.policyVersion());
        assertEquals(RoutingDecision.CONTRACT_VERSION, review.contractVersion());
        List<EligibilityReport.Exclusion> grouped = new ArrayList<>();
        review.exclusionGroups().forEach(group -> grouped.addAll(group.exclusions()));
        grouped.sort(Comparator.comparing(EligibilityReport.Exclusion::key));
        assertEquals(decision.exclusions(), grouped);
    }
}
