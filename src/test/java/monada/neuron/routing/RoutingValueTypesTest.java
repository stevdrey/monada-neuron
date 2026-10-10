package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.features.TaskFeatures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.stream.IntStream;

import static monada.neuron.routing.RoutingFixtures.SUB_A;
import static monada.neuron.routing.RoutingFixtures.SUB_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutingValueTypesTest {

    private static final PolicyParameters PARAMETERS =
            new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "m");

    private static final List<RoutingRule> APPLIED =
            List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.ROUTE_ORDER);
    private static final List<RoutingRule> SKIPPED =
            List.of(RoutingRule.LEARNED_PREFERENCE, RoutingRule.RESOURCE_OBJECTIVE);

    private static final Provenance PROVENANCE = provenance(new StateValidation.Compatible());

    private static Provenance provenance(StateValidation validation) {
        return new Provenance(new DecisionRef("s", "t", "e", "a", "st", 1), "cat", "route-lex", "1", 5,
                new StateBinding("s", "task-features/1", "ev", "1", "m", 0, "route-lex", "1"), validation, PARAMETERS);
    }

    private static RoutingDecision.Selected selected(Provenance provenance, List<RankedCandidate> candidates) {
        return new RoutingDecision.Selected(provenance, SUB_A, "sandboxed", Basis.COLD_START, false, candidates,
                false, APPLIED, SKIPPED, List.of(),
                new CohortBinding("UNKNOWN", "b/1", "m", "task-features/1"));
    }

    @Test
    void decisionRefAndBindingsRejectBadTokensAndNegatives() {
        assertThrows(IllegalArgumentException.class, () -> new DecisionRef("", "t", "e", "a", "st", 1));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRef("s", " t", "e", "a", "st", 1));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRef("s", "t", "e", "a", "st", -1));
        assertThrows(IllegalArgumentException.class,
                () -> new StateBinding("s", "f", "e", "1", "m", -1, "p", "1"));
        assertThrows(IllegalArgumentException.class, () -> new CohortBinding("", "b", "m", "f"));
        assertThrows(IllegalArgumentException.class, () -> new Provenance(PROVENANCE.decisionRef(), "cat", "p", "1",
                -1, PROVENANCE.state(), PROVENANCE.validation(), PARAMETERS));
    }

    @Test
    void incompatibleValidationRequiresUniqueOrderedReasons() {
        assertThrows(IllegalArgumentException.class, () -> new StateValidation.Incompatible(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StateValidation.Incompatible(
                List.of(StateMismatch.MAPPING_VERSION_MISMATCH, StateMismatch.SCOPE_MISMATCH)));
        assertThrows(IllegalArgumentException.class, () -> new StateValidation.Incompatible(
                List.of(StateMismatch.SCOPE_MISMATCH, StateMismatch.SCOPE_MISMATCH)));
    }

    @Test
    void selectedRequiresTheWinnerFirstBoundedRankedCandidatesAndACompatibleState() {
        RankedCandidate a = new RankedCandidate(0, SUB_A, Placement.ONLY_ELIGIBLE);
        selected(PROVENANCE, List.of(a));
        assertThrows(IllegalArgumentException.class, () -> selected(PROVENANCE, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> selected(PROVENANCE, List.of(new RankedCandidate(0, SUB_B, Placement.ROUTE_ORDER))));
        assertThrows(IllegalArgumentException.class,
                () -> selected(PROVENANCE, List.of(a, new RankedCandidate(2, SUB_B, Placement.ROUTE_ORDER))));
        assertThrows(IllegalArgumentException.class,
                () -> selected(PROVENANCE, List.of(a, new RankedCandidate(1, SUB_A, Placement.ROUTE_ORDER))));
        assertThrows(IllegalArgumentException.class,
                () -> selected(provenance(new StateValidation.NotEvaluated()), List.of(a)));
        List<RankedCandidate> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(new RankedCandidate(i, new RouteKey("r", i + 1), Placement.ROUTE_ORDER));
        }
        assertThrows(IllegalArgumentException.class, () -> selected(PROVENANCE, nine));
    }

    @Test
    void abstainAndNoEligibleRouteEnforceTheirStateRules() {
        var incompatible = provenance(new StateValidation.Incompatible(List.of(StateMismatch.SCOPE_MISMATCH)));
        new RoutingDecision.Abstain(incompatible, AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A), List.of(), false,
                List.of());
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Abstain(PROVENANCE,
                AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A), List.of(), false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Abstain(PROVENANCE,
                AbstainReason.POLICY_TRADEOFF_UNRESOLVED, List.of(SUB_A), List.of(), false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Abstain(incompatible,
                AbstainReason.STATE_INCOMPATIBLE, List.of(), List.of(), false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.NoEligibleRoute(PROVENANCE, List.of()));
        var exclusion = new EligibilityReport.Exclusion(SUB_A, EligibilityReason.MISSING_CAPABILITY, List.of());
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.NoEligibleRoute(
                provenance(new StateValidation.NotEvaluated()), List.of(exclusion, exclusion)));
    }

    @Test
    void abstainStoresEligibleRoutesUniqueAndInCanonicalOrder() {
        var incompatible = provenance(new StateValidation.Incompatible(List.of(StateMismatch.SCOPE_MISMATCH)));
        var routes = new ArrayList<>(List.of(new RouteKey("r", 10), new RouteKey("r", 2), new RouteKey("a", 1)));
        var canonical = new RoutingDecision.Abstain(incompatible, AbstainReason.STATE_INCOMPATIBLE,
                List.of(new RouteKey("a", 1), new RouteKey("r", 2), new RouteKey("r", 10)), List.of(), false, List.of());
        Collections.shuffle(routes, new Random(1));

        var shuffled = new RoutingDecision.Abstain(incompatible, AbstainReason.STATE_INCOMPATIBLE, routes, List.of(),
                false, List.of());

        assertEquals(canonical, shuffled);
        assertEquals(new RouteKey("r", 2), shuffled.eligible().get(1));
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Abstain(incompatible,
                AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A, SUB_A), List.of(), false, List.of()));
    }

    @Test
    void exclusionsAreStoredUniqueAndInCanonicalOrder() {
        var first = new EligibilityReport.Exclusion(new RouteKey("a", 1), EligibilityReason.MISSING_CAPABILITY, List.of());
        var second = new EligibilityReport.Exclusion(new RouteKey("r", 2), EligibilityReason.ROUTE_UNAVAILABLE, List.of());
        var third = new EligibilityReport.Exclusion(new RouteKey("r", 10), EligibilityReason.STAGE_INCOMPATIBLE, List.of());
        var notEvaluated = provenance(new StateValidation.NotEvaluated());
        var incompatible = provenance(new StateValidation.Incompatible(List.of(StateMismatch.SCOPE_MISMATCH)));
        var canonical = List.of(first, second, third);
        var shuffled = List.of(third, first, second);

        assertEquals(new RoutingDecision.NoEligibleRoute(notEvaluated, canonical),
                new RoutingDecision.NoEligibleRoute(notEvaluated, shuffled));
        assertEquals(canonical, new RoutingDecision.NoEligibleRoute(notEvaluated, shuffled).exclusions());
        assertEquals(new RoutingDecision.Abstain(incompatible, AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A),
                        List.of(), false, canonical),
                new RoutingDecision.Abstain(incompatible, AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A),
                        List.of(), false, shuffled));
        var ranked = List.of(new RankedCandidate(0, SUB_A, Placement.ONLY_ELIGIBLE));
        assertEquals(selected(PROVENANCE, ranked).exclusions(), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> new RoutingDecision.NoEligibleRoute(notEvaluated, List.of(first, first)));
    }

    @Test
    void selectedAndAbstainRejectARouteThatIsBothEligibleAndExcluded() {
        var conflicting = new EligibilityReport.Exclusion(SUB_A, EligibilityReason.MISSING_CAPABILITY, List.of());
        var other = new EligibilityReport.Exclusion(SUB_B, EligibilityReason.MISSING_CAPABILITY, List.of());
        var ranked = List.of(new RankedCandidate(0, SUB_A, Placement.ONLY_ELIGIBLE));
        var incompatible = provenance(new StateValidation.Incompatible(List.of(StateMismatch.SCOPE_MISMATCH)));

        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Selected(PROVENANCE, SUB_A,
                "sandboxed", Basis.COLD_START, false, ranked, false, APPLIED, SKIPPED,
                List.of(conflicting), new CohortBinding("UNKNOWN", "b/1", "m", "task-features/1")));
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Abstain(incompatible,
                AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A), List.of(), false, List.of(conflicting)));
        new RoutingDecision.Abstain(incompatible, AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A), List.of(), false,
                List.of(other));
    }

    @Test
    void abstainCandidatesMustBeEligible() {
        var compatible = PROVENANCE;
        var candidate = List.of(new RankedCandidate(0, SUB_B, Placement.ONLY_ELIGIBLE));
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Abstain(compatible,
                AbstainReason.POLICY_TRADEOFF_UNRESOLVED, List.of(SUB_A), candidate, false, List.of()));
        new RoutingDecision.Abstain(compatible, AbstainReason.POLICY_TRADEOFF_UNRESOLVED, List.of(SUB_A, SUB_B),
                candidate, false, List.of());
    }

    @Test
    void findLooksUpCohortsByStageBucketAndRouteVersion() {
        var a = new CohortPreference("implement", "M", SUB_A, 0.5, 4);
        var b = new CohortPreference("implement", "S", SUB_A, 0.1, 3);
        var c = new CohortPreference("review", "M", SUB_A, 0.2, 3);
        var d = new CohortPreference("implement", "M", new RouteKey("sub-a", 2), 0.3, 3);
        var snapshot = snapshot(List.of(c, d, a, b));

        assertEquals(a, snapshot.find("implement", "M", SUB_A));
        assertEquals(b, snapshot.find("implement", "S", SUB_A));
        assertEquals(c, snapshot.find("review", "M", SUB_A));
        assertEquals(d, snapshot.find("implement", "M", new RouteKey("sub-a", 2)));
        assertEquals(null, snapshot.find("implement", "L", SUB_A));
        assertEquals(null, snapshot.find("plan", "M", SUB_A));
        assertEquals(null, snapshot.find("implement", "M", SUB_B));
        assertEquals(null, snapshot(List.of()).find("implement", "M", SUB_A));
    }

    private static RoutingDecision.Selected withRules(List<RoutingRule> applied, List<RoutingRule> skipped) {
        return new RoutingDecision.Selected(PROVENANCE, SUB_A, "sandboxed", Basis.COLD_START, false,
                List.of(new RankedCandidate(0, SUB_A, Placement.ONLY_ELIGIBLE)), false, applied, skipped, List.of(),
                new CohortBinding("UNKNOWN", "b/1", "m", "task-features/1"));
    }

    @Test
    void ruleExplanationsMustBeUniqueOrderedDisjointAndComplete() {
        var all = List.of(RoutingRule.values());
        withRules(APPLIED, SKIPPED);
        withRules(all, List.of());
        assertThrows(IllegalArgumentException.class, () -> withRules(
                List.of(RoutingRule.TIER, RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.ROUTE_ORDER),
                SKIPPED));
        assertThrows(IllegalArgumentException.class, () -> withRules(
                List.of(RoutingRule.FALLBACK_PRIORITY, RoutingRule.TIER, RoutingRule.ROUTE_ORDER), SKIPPED));
        assertThrows(IllegalArgumentException.class, () -> withRules(all, SKIPPED));
        assertThrows(IllegalArgumentException.class, () -> withRules(APPLIED, List.of(RoutingRule.LEARNED_PREFERENCE)));
        assertThrows(IllegalArgumentException.class, () -> withRules(
                List.of(RoutingRule.FALLBACK_PRIORITY, RoutingRule.LEARNED_PREFERENCE, RoutingRule.RESOURCE_OBJECTIVE,
                        RoutingRule.ROUTE_ORDER), List.of(RoutingRule.TIER)));
    }

    @Test
    void selectedCohortBindingMustAgreeWithTheStateProvenance() {
        var ranked = List.of(new RankedCandidate(0, SUB_A, Placement.ONLY_ELIGIBLE));
        for (CohortBinding wrong : List.of(
                new CohortBinding("UNKNOWN", "b/1", "m", "task-features/0"),
                new CohortBinding("UNKNOWN", "b/1", "other-mapping", "task-features/1"))) {
            assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Selected(PROVENANCE, SUB_A,
                    "sandboxed", Basis.COLD_START, false, ranked, false, APPLIED, SKIPPED, List.of(), wrong));
        }
        var otherParameters = new Provenance(PROVENANCE.decisionRef(), "cat", "route-lex", "1", 5, PROVENANCE.state(),
                new StateValidation.Compatible(),
                new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "different"));
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Selected(otherParameters, SUB_A,
                "sandboxed", Basis.COLD_START, false, ranked, false, APPLIED, SKIPPED, List.of(),
                new CohortBinding("UNKNOWN", "b/1", "m", "task-features/1")));
    }

    private static List<RouteKey> routes(int count) {
        return IntStream.range(0, count).mapToObj(i -> new RouteKey("r-%02d".formatted(i), 1)).toList();
    }

    @Test
    void decisionsBoundEligibleAndExcludedRoutesByOneCatalog() {
        var incompatible = provenance(new StateValidation.Incompatible(List.of(StateMismatch.SCOPE_MISMATCH)));
        List<EligibilityReport.Exclusion> excluded = routes(32).stream()
                .map(key -> new EligibilityReport.Exclusion(new RouteKey("x-" + key.routeId(), 1),
                        EligibilityReason.MISSING_CAPABILITY, List.of()))
                .toList();

        new RoutingDecision.Abstain(incompatible, AbstainReason.STATE_INCOMPATIBLE, routes(16), List.of(), false,
                excluded.subList(0, 16));
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Abstain(incompatible,
                AbstainReason.STATE_INCOMPATIBLE, routes(17), List.of(), false, excluded.subList(0, 16)));
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Abstain(incompatible,
                AbstainReason.STATE_INCOMPATIBLE, routes(32), List.of(), false, excluded));
        var ranked = List.of(new RankedCandidate(0, SUB_A, Placement.ONLY_ELIGIBLE));
        assertThrows(IllegalArgumentException.class, () -> new RoutingDecision.Selected(PROVENANCE, SUB_A,
                "sandboxed", Basis.COLD_START, false, ranked, false, APPLIED, SKIPPED, excluded,
                new CohortBinding("UNKNOWN", "b/1", "m", "task-features/1")));
    }

    @Test
    void cohortPreferenceValidatesAndNormalizesNegativeZero() {
        assertThrows(IllegalArgumentException.class, () -> new CohortPreference("s", "b", SUB_A, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CohortPreference("s", "b", SUB_A, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new CohortPreference("s", "b", SUB_A, 0.0, -1));
        assertEquals(new CohortPreference("s", "b", SUB_A, 0.0, 0), new CohortPreference("s", "b", SUB_A, -0.0, 0));
    }

    @Test
    void preferenceSnapshotIsBoundedUniqueCanonicalAndImmutable() {
        List<CohortPreference> cohorts = new ArrayList<>();
        for (int i = 0; i < RoutingPreference.MAX_COHORTS; i++) {
            cohorts.add(new CohortPreference("implement", "b" + i, SUB_A, 0, 0));
        }
        RoutingPreference full = snapshot(cohorts);
        assertEquals(256, full.cohorts().size());
        cohorts.add(new CohortPreference("implement", "extra", SUB_A, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> snapshot(cohorts));
        var duplicate = new CohortPreference("implement", "b", SUB_A, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(duplicate, duplicate)));
        assertThrows(UnsupportedOperationException.class, () -> full.cohorts().clear());
        var first = new CohortPreference("implement", "a", SUB_B, 0, 0);
        var second = new CohortPreference("implement", "b", SUB_A, 0, 0);
        assertEquals(snapshot(List.of(first, second)), snapshot(List.of(second, first)));
    }

    private static RoutingPreference snapshot(List<CohortPreference> cohorts) {
        return new RoutingPreference("s", "task-features/1", "ev", "1", "m", "route-lex", "1", 0, cohorts);
    }

    @Test
    void changeSizeMappingBucketsDeterministicallyAndMapsUnknownExplicitly() {
        var mapping = ChangeSizeCohortMapping.DEFAULT;
        assertEquals("UNKNOWN", mapping.bucketOf(TaskFeatures.builder().build()));
        assertEquals("S", mapping.bucketOf(TaskFeatures.builder().changeSize(0).build()));
        assertEquals("S", mapping.bucketOf(TaskFeatures.builder().changeSize(50).build()));
        assertEquals("M", mapping.bucketOf(TaskFeatures.builder().changeSize(51).build()));
        assertEquals("M", mapping.bucketOf(TaskFeatures.builder().changeSize(500).build()));
        assertEquals("L", mapping.bucketOf(TaskFeatures.builder().changeSize(501).build()));
        assertThrows(IllegalArgumentException.class, () -> new ChangeSizeCohortMapping("v", 10, 9));
        assertThrows(IllegalArgumentException.class, () -> new ChangeSizeCohortMapping("v", -1, 9));
    }

    @Test
    void policyConfigurationIsValidated() {
        var definition = RoutingStateDefinition.reference();
        assertThrows(IllegalArgumentException.class, () -> new LexicographicRoutingPolicy(definition, 0,
                OptionalInt.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new LexicographicRoutingPolicy(definition, 1,
                OptionalInt.of(0), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ResourceObjective(" cost", ResourceObjective.Direction.MINIMIZE));
        assertThrows(IllegalArgumentException.class, () -> new RoutingStateDefinition("", ChangeSizeCohortMapping.DEFAULT));
    }
}
