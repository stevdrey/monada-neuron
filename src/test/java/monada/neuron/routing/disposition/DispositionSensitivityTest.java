package monada.neuron.routing.disposition;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.Placement;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.disposition.AdviceDisposition.AlternativeProposed;
import monada.neuron.routing.disposition.AdviceDisposition.Followed;
import monada.neuron.routing.disposition.DispositionFixtures.Base;
import monada.neuron.routing.disposition.DispositionFixtures.Parts;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.routing.receipt.AdviceReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

import static monada.neuron.routing.disposition.DispositionFixtures.SUB_A;
import static monada.neuron.routing.disposition.DispositionFixtures.SUB_B;
import static monada.neuron.routing.disposition.DispositionFixtures.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A disposition refers to one exact piece of advice: the digest covers every decision component, so a change anywhere
 * outside the {@code DecisionRef} (same ref, other advice) must break {@code refersTo}, and the standing derivation
 * must hold at the catalog bound and on decisions a real policy produced.
 */
class DispositionSensitivityTest {

    /** Leaves every variant shares, each changed to another valid value; none touches the {@code DecisionRef}. */
    private static final List<Consumer<Parts>> SHARED_LEAVES = List.of(
            parts -> parts.catalogVersion = "cat-demo-8",
            parts -> parts.policyVersion = "pv-2",
            parts -> parts.cutoff = 51,
            parts -> parts.processedCutoff = 41,
            parts -> parts.producedById = "producer-2",
            parts -> parts.stageKind = Optional.empty(),
            parts -> parts.minSupport = 4,
            parts -> parts.exclusions = List.of(DispositionFixtures.GONE, new EligibilityReport.Exclusion(
                    new RouteKey("ex-other", 1), EligibilityReason.ROUTE_UNAVAILABLE, List.of())));

    private static List<AdviceDisposition> allFour(AdviceReceipt receipt, DecisionRef ref) {
        List<AdviceDisposition> dispositions = new ArrayList<>();
        DispositionResult followed = AdviceDisposition.followed(receipt, ref);
        if (followed instanceof DispositionResult.Bound(AdviceDisposition disposition)) {
            dispositions.add(disposition);
        }
        dispositions.add(bound(AdviceDisposition.alternative(receipt, ref, UNKNOWN)));
        dispositions.add(bound(AdviceDisposition.deferred(receipt, ref)));
        dispositions.add(bound(AdviceDisposition.declined(receipt, ref)));
        return dispositions;
    }

    private static AdviceDisposition bound(DispositionResult result) {
        return assertInstanceOf(DispositionResult.Bound.class, result).disposition();
    }

    // covers: AC-5
    @ParameterizedTest
    @EnumSource(Base.class)
    void aChangeAnywhereOutsideTheRefBreaksRefersToWhileTheRefStillMatches(Base base) {
        AdviceReceipt original = base.receipt();
        List<AdviceDisposition> dispositions = allFour(original, base.ref());
        List<Consumer<Parts>> leaves = new ArrayList<>(SHARED_LEAVES);
        switch (base) {
            case SELECTED, SELECTED_FULL, SELECTED_TRUNCATED, SELECTED_SAME_ID_OTHER_VERSION -> {
                leaves.add(parts -> parts.executionMode = "hosted");
                leaves.add(parts -> parts.overflowUsed = true);
            }
            case ABSTAIN_TRADEOFF -> leaves.add(parts -> parts.cohortBucket = "SMALL");
            case ABSTAIN_INCOMPATIBLE -> leaves.add(parts -> parts.mismatches = List.of(
                    StateMismatch.FEATURE_SCHEMA_MISMATCH, StateMismatch.MAPPING_VERSION_MISMATCH));
            case NO_ELIGIBLE_ROUTE, NO_ELIGIBLE_ROUTE_EMPTY -> {
                // Only the shared leaves apply: the variant has no component of its own beyond the exclusions.
            }
        }

        for (Consumer<Parts> leaf : leaves) {
            Parts parts = base.parts();
            leaf.accept(parts);
            AdviceReceipt changed = AdviceReceipt.of(base.build(parts));

            assertEquals(base.ref(), changed.decision().provenance().decisionRef());
            assertNotEquals(original.sha256(), changed.sha256());
            for (AdviceDisposition disposition : dispositions) {
                assertFalse(disposition.refersTo(changed), disposition + " vs " + changed);
                assertTrue(disposition.refersTo(original));
            }
        }
    }

    // covers: AC-5
    @Test
    void aDispositionOfOneShapeNeverRefersToAnotherShapesReceipt() {
        for (Base one : Base.values()) {
            List<AdviceDisposition> dispositions = allFour(one.receipt(), one.ref());
            for (Base other : Base.values()) {
                if (other == one) {
                    continue;
                }
                for (AdviceDisposition disposition : dispositions) {
                    assertFalse(disposition.refersTo(other.receipt()), one + " -> " + other + ": " + disposition);
                }
            }
        }
    }

    // covers: AC-1
    @Test
    void dispositionsOfDifferentShapesAreNeverEqual() {
        List<AdviceDisposition> all = new ArrayList<>();
        for (Base base : Base.values()) {
            all.addAll(allFour(base.receipt(), base.ref()));
        }

        for (int i = 0; i < all.size(); i++) {
            for (int j = 0; j < all.size(); j++) {
                assertEquals(i == j, all.get(i).equals(all.get(j)), all.get(i) + " vs " + all.get(j));
            }
        }
    }

    private static List<EligibilityReport.Exclusion> exclusions(int count) {
        List<EligibilityReport.Exclusion> exclusions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            exclusions.add(new EligibilityReport.Exclusion(new RouteKey("x-" + (char) ('a' + i), 1),
                    EligibilityReason.ROUTE_UNAVAILABLE, List.of()));
        }
        return exclusions;
    }

    // covers: AC-3
    @Test
    void standingIsDerivedAtTheCatalogBoundForASelection() {
        Parts parts = new Parts().full().truncated();
        int exclusionCount = RouteCatalog.MAX_ROUTES - RoutingDecision.MAX_CANDIDATES - 1;
        parts.exclusions = exclusions(exclusionCount);
        RoutingDecision decision = parts.selected();
        AdviceReceipt receipt = AdviceReceipt.of(decision);
        DecisionRef ref = decision.provenance().decisionRef();
        assertEquals(RouteCatalog.MAX_ROUTES, RoutingDecision.MAX_CANDIDATES + exclusionCount + 1);

        var lastCandidate = assertInstanceOf(AlternativeProposed.class,
                bound(AdviceDisposition.alternative(receipt, ref, new RouteKey("r-7", 1))));
        var lastExclusion = assertInstanceOf(AlternativeProposed.class,
                bound(AdviceDisposition.alternative(receipt, ref, parts.exclusions.getLast().key())));
        var cutByTruncation = assertInstanceOf(AlternativeProposed.class,
                bound(AdviceDisposition.alternative(receipt, ref, new RouteKey("r-8", 1))));

        assertEquals(AlternativeStanding.ELIGIBLE_IN_DECISION, lastCandidate.standing());
        assertEquals(AlternativeStanding.EXCLUDED_IN_DECISION, lastExclusion.standing());
        assertEquals(AlternativeStanding.HOST_CLAIM, cutByTruncation.standing());
        assertTrue(lastExclusion.refersTo(receipt));
    }

    // covers: AC-3
    @Test
    void standingIsDerivedAtTheCatalogBoundForAnAbstention() {
        Parts parts = new Parts();
        List<RouteKey> eligible = new ArrayList<>();
        List<RankedCandidate> ranked = new ArrayList<>();
        for (int i = 0; i < RoutingDecision.MAX_CANDIDATES; i++) {
            RouteKey key = new RouteKey("e-" + i, 1);
            eligible.add(key);
            ranked.add(new RankedCandidate(i, key, Placement.ROUTE_ORDER));
        }
        parts.eligible = eligible;
        parts.tradeoffCandidates = ranked;
        parts.exclusions = exclusions(RouteCatalog.MAX_ROUTES - RoutingDecision.MAX_CANDIDATES);
        RoutingDecision decision = parts.tradeoff();
        AdviceReceipt receipt = AdviceReceipt.of(decision);
        DecisionRef ref = decision.provenance().decisionRef();

        var lastEligible = assertInstanceOf(AlternativeProposed.class,
                bound(AdviceDisposition.alternative(receipt, ref, eligible.getLast())));
        var lastExclusion = assertInstanceOf(AlternativeProposed.class,
                bound(AdviceDisposition.alternative(receipt, ref, parts.exclusions.getLast().key())));
        var unknown = assertInstanceOf(AlternativeProposed.class,
                bound(AdviceDisposition.alternative(receipt, ref, UNKNOWN)));

        assertEquals(AlternativeStanding.ELIGIBLE_IN_DECISION, lastEligible.standing());
        assertEquals(AlternativeStanding.EXCLUDED_IN_DECISION, lastExclusion.standing());
        assertEquals(AlternativeStanding.HOST_CLAIM, unknown.standing());
    }

    private static RouteCatalog catalog(boolean reversed) {
        List<CatalogEntry> entries = new ArrayList<>();
        for (String id : List.of("route-a", "route-b", "route-c")) {
            entries.add(new CatalogEntry(RouteDescriptor.builder(id, 1).stages(List.of("implement"))
                    .executionModes(List.of("sandboxed")).locality("hosted").tier(id.equals("route-c") ? 2 : 1)
                    .capabilities(List.of(id.equals("route-c") ? "python" : "java")).build(), Availability.AVAILABLE, 1));
        }
        if (reversed) {
            Collections.reverse(entries);
        }
        return RouteCatalog.of("cat-1", entries);
    }

    private static RoutingDecision decide(LexicographicRoutingPolicy policy, String capability, boolean reversed) {
        var request = new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope-w1", "task-3", "exec-1", "att-1",
                "stage-2", "implement", 7L, "src", "ctx", "con", "eval", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(capability), List.of(), List.of("hosted"), List.of("sandboxed"), 0L),
                false, 0L);
        return policy.decide(request, catalog(reversed),
                RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion()));
    }

    // covers: AC-2, AC-3, AC-5
    @Test
    void dispositionsBindToDecisionsARealPolicyProducedAndIgnoreCatalogInputOrder() {
        var reference = LexicographicRoutingPolicy.reference();
        var capped = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(),
                LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.of(1), Optional.empty());
        RoutingDecision selected = decide(reference, "java", false);
        RoutingDecision abstained = decide(capped, "python", false);
        RoutingDecision none = decide(reference, "rust", false);
        assertInstanceOf(RoutingDecision.Selected.class, selected);
        assertInstanceOf(RoutingDecision.Abstain.class, abstained);
        assertInstanceOf(RoutingDecision.NoEligibleRoute.class, none);
        AdviceReceipt selectedReceipt = AdviceReceipt.of(selected);
        DecisionRef ref = selected.provenance().decisionRef();

        var followed = assertInstanceOf(Followed.class, bound(AdviceDisposition.followed(selectedReceipt, ref)));
        var runnerUp = assertInstanceOf(AlternativeProposed.class,
                bound(AdviceDisposition.alternative(selectedReceipt, ref, new RouteKey("route-b", 1))));
        var excluded = assertInstanceOf(AlternativeProposed.class,
                bound(AdviceDisposition.alternative(selectedReceipt, ref, new RouteKey("route-c", 1))));
        var afterNone = assertInstanceOf(AlternativeProposed.class, bound(AdviceDisposition.alternative(
                AdviceReceipt.of(none), none.provenance().decisionRef(), new RouteKey("route-a", 1))));

        assertEquals(Optional.of(new RouteKey("route-a", 1)), followed.advice().advisedRoute());
        assertEquals(AlternativeStanding.ELIGIBLE_IN_DECISION, runnerUp.standing());
        assertEquals(AlternativeStanding.EXCLUDED_IN_DECISION, excluded.standing());
        assertEquals(AlternativeStanding.EXCLUDED_IN_DECISION, afterNone.standing());
        assertEquals(new DispositionResult.Rejected(DispositionRejection.ADVICE_NOT_SELECTED),
                AdviceDisposition.followed(AdviceReceipt.of(abstained), abstained.provenance().decisionRef()));
        assertTrue(followed.refersTo(AdviceReceipt.of(decide(reference, "java", true))));
        assertEquals(followed, bound(AdviceDisposition.followed(AdviceReceipt.of(decide(reference, "java", true)), ref)));
        assertFalse(followed.refersTo(AdviceReceipt.of(decide(capped, "java", false))));
    }

    // covers: AC-4
    @Test
    void anAlternativeOnAnAbstentionIsNeverTheAdviceEvenWhenEligible() {
        AdviceReceipt receipt = Base.ABSTAIN_TRADEOFF.receipt();

        var proposed = assertInstanceOf(AlternativeProposed.class,
                bound(AdviceDisposition.alternative(receipt, Base.ABSTAIN_TRADEOFF.ref(), SUB_A)));

        assertEquals(Optional.empty(), proposed.advice().advisedRoute());
        assertEquals(AlternativeStanding.ELIGIBLE_IN_DECISION, proposed.standing());
        assertEquals(SUB_A, proposed.route());
        assertNotEquals(proposed, bound(AdviceDisposition.alternative(receipt, Base.ABSTAIN_TRADEOFF.ref(), SUB_B)));
    }
}
