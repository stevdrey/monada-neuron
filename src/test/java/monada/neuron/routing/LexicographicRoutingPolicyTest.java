package monada.neuron.routing;

import monada.neuron.routing.RoutingDecision.Abstain;
import monada.neuron.routing.RoutingDecision.NoEligibleRoute;
import monada.neuron.routing.RoutingDecision.Selected;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.ResourceEstimate;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.TaskFeatures;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

import static monada.neuron.routing.RoutingFixtures.API_X;
import static monada.neuron.routing.RoutingFixtures.SUB_A;
import static monada.neuron.routing.RoutingFixtures.SUB_B;
import static monada.neuron.routing.RoutingFixtures.base;
import static monada.neuron.routing.RoutingFixtures.cohort;
import static monada.neuron.routing.RoutingFixtures.cost;
import static monada.neuron.routing.RoutingFixtures.demo;
import static monada.neuron.routing.RoutingFixtures.demoWith;
import static monada.neuron.routing.RoutingFixtures.empty;
import static monada.neuron.routing.RoutingFixtures.entry;
import static monada.neuron.routing.RoutingFixtures.preference;
import static monada.neuron.routing.RoutingFixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicographicRoutingPolicyTest {

    private final LexicographicRoutingPolicy policy = LexicographicRoutingPolicy.reference();

    private static final List<String> JAVA = List.of("java");

    private Selected selected(RoutingRequest request, RouteCatalog catalog, RoutingPreference preference) {
        return assertInstanceOf(Selected.class, policy.decide(request, catalog, preference));
    }

    private static List<RouteKey> keys(Selected decision) {
        return decision.candidates().stream().map(RankedCandidate::key).toList();
    }

    private LexicographicRoutingPolicy withObjective(ResourceObjective.Direction direction) {
        return new LexicographicRoutingPolicy(RoutingStateDefinition.reference(), 3, OptionalInt.empty(),
                Optional.of(new ResourceObjective("cost", direction)));
    }

    // 9.1 cold start

    @Test
    void coldStartSelectsCanonicalFirstAndExplainsEveryRule() {
        RoutingRequest request = request(JAVA, false);
        Selected decision = selected(request, demo(), empty(request));

        assertEquals(SUB_A, decision.route());
        assertEquals("sandboxed", decision.executionMode());
        assertEquals(Basis.COLD_START, decision.basis());
        assertFalse(decision.overflowUsed());
        assertEquals(List.of(SUB_A, SUB_B), keys(decision));
        assertEquals(List.of(Placement.ROUTE_ORDER, Placement.ROUTE_ORDER),
                decision.candidates().stream().map(RankedCandidate::placement).toList());
        assertFalse(decision.candidatesTruncated());
        assertEquals(List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.ROUTE_ORDER),
                decision.rulesApplied());
        assertEquals(List.of(RoutingRule.LEARNED_PREFERENCE, RoutingRule.RESOURCE_OBJECTIVE), decision.rulesSkipped());
        assertEquals(1, decision.exclusions().size());
        assertEquals(API_X, decision.exclusions().getFirst().key());
        assertEquals(EligibilityReason.OVERFLOW_NOT_PERMITTED, decision.exclusions().getFirst().primaryReason());
        assertEquals(new CohortBinding("UNKNOWN", "change-size/1", "routing-state/1", "task-features/1"),
                decision.cohortBinding());
        assertEquals(new DecisionRef("scope-demo", "task-17", "exec-1", "att-1", "s-impl", 101L),
                decision.provenance().decisionRef());
        assertEquals("cat-demo-7", decision.provenance().catalogVersion());
        assertEquals("route-lex", decision.provenance().policyId());
        assertEquals(new StateValidation.Compatible(), decision.provenance().validation());
    }

    // 9.2 no eligible route, then explicit overflow

    @Test
    void noEligibleRouteListsEveryRouteAndIgnoresAnIncompatibleState() {
        RoutingRequest request = request(List.of("java", "long-context"), false);
        RoutingPreference foreign = new RoutingPreference("other-scope", "task-features/1", "forge-gates", "1",
                "routing-state/1", "route-lex", "1", 999L, List.of());

        NoEligibleRoute decision = assertInstanceOf(NoEligibleRoute.class, policy.decide(request, demo(), foreign));

        assertEquals(3, decision.exclusions().size());
        assertEquals(EligibilityReason.MISSING_CAPABILITY, decision.exclusions().get(1).primaryReason());
        assertEquals(EligibilityReason.OVERFLOW_NOT_PERMITTED, decision.exclusions().getFirst().primaryReason());
        assertEquals(new StateValidation.NotEvaluated(), decision.provenance().validation());
        assertEquals("other-scope", decision.provenance().state().scopeId());
    }

    @Test
    void explicitOverflowPermissionSelectsTheApiRouteAndRecordsIt() {
        RoutingRequest request = request(List.of("java", "long-context"), true);
        Selected decision = selected(request, demo(), empty(request));

        assertEquals(API_X, decision.route());
        assertTrue(decision.overflowUsed());
        assertEquals(Placement.ONLY_ELIGIBLE, decision.candidates().getFirst().placement());
        assertEquals(2, decision.exclusions().size());
    }

    @Test
    void emptyCatalogHasNoEligibleRoute() {
        RoutingRequest request = request(JAVA, false);
        NoEligibleRoute decision = assertInstanceOf(NoEligibleRoute.class,
                policy.decide(request, RouteCatalog.of("empty", List.of()), empty(request)));
        assertTrue(decision.exclusions().isEmpty());
    }

    // subscription preference

    @Test
    void subscriptionTierBeatsACheaperPermittedApiRoute() {
        RoutingRequest request = request(JAVA, true);
        RouteCatalog catalog = demoWith(List.of(cost(SUB_A, 900, "usd-cents"), cost(SUB_B, 900, "usd-cents"),
                cost(API_X, 1, "usd-cents")));

        Selected decision = assertInstanceOf(Selected.class, withObjective(ResourceObjective.Direction.MINIMIZE)
                .decide(request, catalog, empty(request)));

        assertEquals(SUB_A, decision.route());
        assertEquals(List.of(SUB_A, SUB_B, API_X), keys(decision));
        assertEquals(Placement.TIER, decision.candidates().get(2).placement());
        assertFalse(decision.overflowUsed());
    }

    @Test
    void learnedPreferenceCannotPromoteAcrossTiersOrBypassEligibility() {
        RoutingRequest request = request(JAVA, true);
        RoutingPreference learned = preference(request, 40, cohort(SUB_A, 0.0, 5), cohort(SUB_B, 0.0, 5),
                cohort(API_X, 9.0, 50));

        assertEquals(SUB_A, selected(request, demo(), learned).route());

        RoutingRequest noOverflow = request(JAVA, false);
        Selected without = selected(noOverflow, demo(), preference(noOverflow, 40, cohort(SUB_A, 0.0, 5),
                cohort(SUB_B, 0.0, 5), cohort(API_X, 9.0, 50)));
        assertEquals(List.of(SUB_A, SUB_B), keys(without));
    }

    // priorities, ties and numeric version order

    @Test
    void fallbackPriorityOrdersWithinATier() {
        RoutingRequest request = request(JAVA, false);
        RouteCatalog catalog = RouteCatalog.of("cat-prio", List.of(entry(base("sub-a", 1).build(), 2),
                entry(base("sub-b", 1).build(), 1)));

        Selected decision = selected(request, catalog, empty(request));

        assertEquals(SUB_B, decision.route());
        assertEquals(Basis.HOST_PRIORITY, decision.basis());
        assertEquals(Placement.FALLBACK_PRIORITY, decision.candidates().getFirst().placement());
    }

    @Test
    void finalTieBreakIsNumericRouteVersionNotLexical() {
        RoutingRequest request = request(JAVA, false);
        RouteCatalog catalog = RouteCatalog.of("cat-ver", List.of(entry(base("r", 10).build(), 1),
                entry(base("r", 2).build(), 1)));

        assertEquals(new RouteKey("r", 2), selected(request, catalog, empty(request)).route());
    }

    // learned preference

    @Test
    void learnedPreferenceReordersTiedCandidatesWhenAllHaveEnoughSupport() {
        RoutingRequest request = request(JAVA, false);
        RoutingPreference learned = preference(request, 40, cohort(SUB_A, 0.1, 3), cohort(SUB_B, 0.4, 3));

        Selected decision = selected(request, demo(), learned);

        assertEquals(SUB_B, decision.route());
        assertEquals(Basis.LEARNED_PREFERENCE, decision.basis());
        assertEquals(List.of(SUB_B, SUB_A), keys(decision));
        assertTrue(decision.rulesApplied().contains(RoutingRule.LEARNED_PREFERENCE));
        assertEquals(List.of(RoutingRule.RESOURCE_OBJECTIVE), decision.rulesSkipped());
    }

    @Test
    void learnedPreferenceIsSkippedForAllWhenAnyCandidateLacksSupport() {
        RoutingRequest request = request(JAVA, false);
        for (RoutingPreference thin : List.of(
                preference(request, 40, cohort(SUB_A, 0.1, 3), cohort(SUB_B, 0.4, 2)),
                preference(request, 40, cohort(SUB_B, 0.4, 9)),
                preference(request, 40, new CohortPreference("implement", "L", SUB_B, 0.4, 9),
                        new CohortPreference("review", "UNKNOWN", SUB_B, 0.4, 9),
                        new CohortPreference("implement", "UNKNOWN", new RouteKey("sub-b", 2), 0.4, 9)))) {
            Selected decision = selected(request, demo(), thin);
            assertEquals(SUB_A, decision.route());
            assertEquals(Basis.COLD_START, decision.basis());
            assertTrue(decision.rulesSkipped().contains(RoutingRule.LEARNED_PREFERENCE));
        }
    }

    @Test
    void learnedTieFallsThroughToRouteOrder() {
        RoutingRequest request = request(JAVA, false);
        Selected decision = selected(request, demo(),
                preference(request, 40, cohort(SUB_A, 0.0, 3), cohort(SUB_B, -0.0, 3)));
        assertEquals(SUB_A, decision.route());
        assertEquals(Placement.ROUTE_ORDER, decision.candidates().getFirst().placement());
    }

    // resource objective

    @Test
    void knownComparableEstimatesRefineTiedCandidates() {
        RoutingRequest request = request(JAVA, false);
        RouteCatalog catalog = demoWith(List.of(cost(SUB_A, 50, "usd-cents"), cost(SUB_B, 20, "usd-cents")));

        Selected minimized = assertInstanceOf(Selected.class,
                withObjective(ResourceObjective.Direction.MINIMIZE).decide(request, catalog, empty(request)));
        assertEquals(SUB_B, minimized.route());
        assertEquals(Basis.RESOURCE_OBJECTIVE, minimized.basis());
        assertTrue(minimized.rulesApplied().contains(RoutingRule.RESOURCE_OBJECTIVE));

        Selected maximized = assertInstanceOf(Selected.class,
                withObjective(ResourceObjective.Direction.MAXIMIZE).decide(request, catalog, empty(request)));
        assertEquals(SUB_A, maximized.route());
    }

    @Test
    void unknownNotMeasuredMissingOrIncomparableEstimatesNeverWin() {
        RoutingRequest request = request(JAVA, false);
        LexicographicRoutingPolicy minimize = withObjective(ResourceObjective.Direction.MINIMIZE);
        ResourceValue unknown = new ResourceValue.Unknown();
        ResourceValue notMeasured = new ResourceValue.NotMeasured();
        List<List<ResourceEstimate>> cases = List.of(
                List.of(cost(SUB_A, 50, "usd-cents"), cost(SUB_B, unknown)),
                List.of(cost(SUB_A, 50, "usd-cents"), cost(SUB_B, notMeasured)),
                List.of(cost(SUB_A, 50, "usd-cents")),
                List.of(cost(SUB_A, 50, "usd-cents"), cost(SUB_B, 1, "eur-cents")),
                List.of(cost(SUB_A, unknown), cost(SUB_B, notMeasured)));
        for (List<ResourceEstimate> estimates : cases) {
            Selected decision = assertInstanceOf(Selected.class,
                    minimize.decide(request, demoWith(estimates), empty(request)));
            assertEquals(SUB_A, decision.route(), estimates.toString());
            assertEquals(Basis.COLD_START, decision.basis());
            assertEquals(List.of(RoutingRule.LEARNED_PREFERENCE, RoutingRule.RESOURCE_OBJECTIVE),
                    decision.rulesSkipped());
        }
    }

    @Test
    void aKnownZeroIsARealZeroAndDimensionsAreNotMixed() {
        RoutingRequest request = request(JAVA, false);
        RouteCatalog catalog = demoWith(List.of(cost(SUB_A, 5, "usd-cents"), cost(SUB_B, 0, "usd-cents"),
                new ResourceEstimate(SUB_A, "latency", "ms", new ResourceValue.Known(1, ResourceValue.Provenance.ESTIMATED))));
        Selected decision = assertInstanceOf(Selected.class,
                withObjective(ResourceObjective.Direction.MINIMIZE).decide(request, catalog, empty(request)));
        assertEquals(SUB_B, decision.route());
    }

    @Test
    void resourceObjectiveNeverOverridesTierOrLearnedPreference() {
        RoutingRequest request = request(JAVA, false);
        RouteCatalog catalog = demoWith(List.of(cost(SUB_A, 50, "usd-cents"), cost(SUB_B, 20, "usd-cents")));
        Selected decision = assertInstanceOf(Selected.class,
                withObjective(ResourceObjective.Direction.MINIMIZE).decide(request, catalog,
                        preference(request, 40, cohort(SUB_A, 0.5, 3), cohort(SUB_B, 0.1, 3))));
        assertEquals(SUB_A, decision.route());
        assertEquals(Basis.LEARNED_PREFERENCE, decision.basis());
    }

    // abstention

    @Test
    void tierAboveTheHostCapAbstainsWithTheRankedCandidates() {
        RoutingRequest request = request(List.of("long-context"), true);
        var capped = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(), 3, OptionalInt.of(1),
                Optional.empty());

        Abstain decision = assertInstanceOf(Abstain.class, capped.decide(request, demo(), empty(request)));

        assertEquals(AbstainReason.POLICY_TRADEOFF_UNRESOLVED, decision.reason());
        assertEquals(List.of(API_X), decision.eligible());
        assertEquals(API_X, decision.candidates().getFirst().key());
        assertEquals(2, decision.exclusions().size());
        assertInstanceOf(Selected.class, new LexicographicRoutingPolicy(RoutingStateDefinition.reference(), 3,
                OptionalInt.of(2), Optional.empty()).decide(request, demo(), empty(request)));
        assertInstanceOf(Selected.class, policy.decide(request, demo(), empty(request)));
    }

    @Test
    void anIncompatibleStateAbstainsAndRetainsEveryMismatchInOrder() {
        RoutingRequest request = request(JAVA, false);
        RoutingPreference allWrong = new RoutingPreference("other", "task-features/0", "other-eval", "9",
                "routing-state/0", "route-lex", "1", 51L, List.of());

        Abstain decision = assertInstanceOf(Abstain.class, policy.decide(request, demo(), allWrong));

        assertEquals(AbstainReason.STATE_INCOMPATIBLE, decision.reason());
        assertEquals(new StateValidation.Incompatible(List.of(StateMismatch.SCOPE_MISMATCH,
                StateMismatch.FEATURE_SCHEMA_MISMATCH, StateMismatch.EVALUATION_POLICY_MISMATCH,
                StateMismatch.MAPPING_VERSION_MISMATCH, StateMismatch.PROCESSED_CUTOFF_NEWER)),
                decision.provenance().validation());
        assertEquals(List.of(SUB_A, SUB_B), decision.eligible());
        assertTrue(decision.candidates().isEmpty());
        assertEquals(51L, decision.provenance().state().processedCutoff());
    }

    @Test
    void eachAdmissionRuleIsCheckedOnItsOwn() {
        RoutingRequest request = request(JAVA, false);
        var base = empty(request);
        List<RoutingPreference> variants = List.of(
                new RoutingPreference("x", base.featureSchemaVersion(), base.evaluationPolicyId(),
                        base.evaluationPolicyVersion(), base.mappingVersion(), "p", "1", 0, List.of()),
                new RoutingPreference(base.scopeId(), "x", base.evaluationPolicyId(),
                        base.evaluationPolicyVersion(), base.mappingVersion(), "p", "1", 0, List.of()),
                new RoutingPreference(base.scopeId(), base.featureSchemaVersion(), "x",
                        base.evaluationPolicyVersion(), base.mappingVersion(), "p", "1", 0, List.of()),
                new RoutingPreference(base.scopeId(), base.featureSchemaVersion(), base.evaluationPolicyId(),
                        "x", base.mappingVersion(), "p", "1", 0, List.of()),
                new RoutingPreference(base.scopeId(), base.featureSchemaVersion(), base.evaluationPolicyId(),
                        base.evaluationPolicyVersion(), "x", "p", "1", 0, List.of()),
                preference(request, 51));
        List<StateMismatch> expected = List.of(StateMismatch.SCOPE_MISMATCH, StateMismatch.FEATURE_SCHEMA_MISMATCH,
                StateMismatch.EVALUATION_POLICY_MISMATCH, StateMismatch.EVALUATION_POLICY_MISMATCH,
                StateMismatch.MAPPING_VERSION_MISMATCH, StateMismatch.PROCESSED_CUTOFF_NEWER);
        for (int i = 0; i < variants.size(); i++) {
            assertEquals(List.of(expected.get(i)), variants.get(i).mismatches(request, policy.definition()), "case " + i);
        }
        assertTrue(preference(request, 50).mismatches(request, policy.definition()).isEmpty());
    }

    @Test
    void policyProvenanceOfTheSnapshotIsNotAnAdmissionKey() {
        RoutingRequest request = request(JAVA, false);
        var other = new RoutingPreference("scope-demo", "task-features/1", "forge-gates", "1", "routing-state/1",
                "some-other-policy", "7", 3, List.of());
        Selected decision = selected(request, demo(), other);
        assertEquals("some-other-policy", decision.provenance().state().producedByPolicyId());
        assertEquals("route-lex", decision.provenance().policyId());
    }

    // safety and determinism

    @Test
    void cheapInvalidCandidatesCanNeverWin() {
        RoutingRequest request = request(JAVA, false);
        RouteCatalog catalog = new RouteCatalog("cat-bad", List.of(
                entry(base("sub-a", 1).build(), 5),
                new CatalogEntry(base("cheap-down", 1).build(), Availability.UNAVAILABLE, 0),
                new CatalogEntry(base("cheap-unknown", 1).build(), Availability.UNKNOWN, 0),
                entry(base("cheap-nocap", 1).capabilities(List.of("rust")).build(), 0),
                entry(RoutingFixtures.apiX(), 0)),
                List.of(cost(SUB_A, 999, "usd-cents"), cost(new RouteKey("cheap-down", 1), 0, "usd-cents"),
                        cost(new RouteKey("cheap-unknown", 1), 0, "usd-cents"),
                        cost(new RouteKey("cheap-nocap", 1), 0, "usd-cents"), cost(API_X, 0, "usd-cents")));

        Selected decision = assertInstanceOf(Selected.class, withObjective(ResourceObjective.Direction.MINIMIZE)
                .decide(request, catalog, preference(request, 1, cohort(API_X, 99, 99))));

        assertEquals(SUB_A, decision.route());
        assertEquals(List.of(SUB_A), keys(decision));
        assertEquals(4, decision.exclusions().size());
    }

    @Test
    void inputPermutationsGiveTheSameDecisionAndExplanation() {
        TaskFeatures features = TaskFeatures.builder().stageKind("implement").changeSize(120).build();
        RoutingRequest request = request(features, JAVA, true, 50L);
        List<CatalogEntry> entries = new ArrayList<>();
        List<ResourceEstimate> estimates = new ArrayList<>();
        List<CohortPreference> cohorts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            RouteKey key = new RouteKey("r-" + (i % 6), 1 + i / 6);
            entries.add(entry(base(key.routeId(), key.routeVersion()).tier(1 + i % 2).build(), i % 3));
            estimates.add(cost(key, 100 - (i % 4) * 10, "usd-cents"));
            cohorts.add(new CohortPreference("implement", "M", key, (i % 5) * 0.1, 3 + i % 2));
        }
        var objective = withObjective(ResourceObjective.Direction.MINIMIZE);
        RoutingPreference reference = new RoutingPreference("scope-demo", "task-features/1", "forge-gates", "1",
                "routing-state/1", "route-lex", "1", 10, cohorts);
        RoutingDecision expected = objective.decide(request, new RouteCatalog("cat-perm", entries, estimates), reference);
        assertInstanceOf(Selected.class, expected);

        Random random = new Random(62);
        for (int round = 0; round < 50; round++) {
            Collections.shuffle(entries, random);
            Collections.shuffle(estimates, random);
            Collections.shuffle(cohorts, random);
            RoutingPreference shuffled = new RoutingPreference("scope-demo", "task-features/1", "forge-gates", "1",
                    "routing-state/1", "route-lex", "1", 10, cohorts);
            assertEquals(expected, objective.decide(request, new RouteCatalog("cat-perm", entries, estimates), shuffled));
        }
    }

    @Test
    void moreThanEightEligibleRoutesTruncateTheExplanationButNotTheChoice() {
        RoutingRequest request = request(JAVA, false);
        List<CatalogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entries.add(entry(base("route-" + (char) ('a' + i), 1).build(), 1));
        }
        Selected decision = selected(request, RouteCatalog.of("cat-many", entries), empty(request));
        assertEquals(8, decision.candidates().size());
        assertTrue(decision.candidatesTruncated());
        assertEquals(new RouteKey("route-a", 1), decision.route());
    }

    @Test
    void repeatedDecisionsAreEqual() {
        RoutingRequest request = request(JAVA, false);
        assertEquals(policy.decide(request, demo(), empty(request)), policy.decide(request, demo(), empty(request)));
    }

    @Test
    void maximumSizeDecisionAllocatesBoundedMemory(TestReporter reporter) {
        RoutingRequest request = request(JAVA, true);
        List<CatalogEntry> entries = new ArrayList<>();
        List<ResourceEstimate> estimates = new ArrayList<>();
        for (int i = 0; i < RouteCatalog.MAX_ROUTES; i++) {
            RouteKey key = new RouteKey(String.format("route-%02d", i), 1);
            entries.add(entry(base(key.routeId(), 1).build(), 1));
            estimates.add(cost(key, i, "usd-cents"));
        }
        RouteCatalog catalog = new RouteCatalog("cat-max", entries, estimates);
        RoutingPreference preference = empty(request);
        var objective = withObjective(ResourceObjective.Direction.MINIMIZE);
        for (int i = 0; i < 20_000; i++) {
            objective.decide(request, catalog, preference);
        }
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(threads.isThreadAllocatedMemorySupported(), "thread allocation tracking unsupported");
        threads.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(id);
        RoutingDecision decision = objective.decide(request, catalog, preference);
        long after = threads.getThreadAllocatedBytes(id);
        assertTrue(before >= 0 && after >= before, "allocation counter unavailable");
        assertInstanceOf(Selected.class, decision);
        long allocated = after - before;
        reporter.publishEntry("allocatedBytes", Long.toString(allocated));
        // Regression guard only, not a benchmark; the measured figure is documented in the contract.
        assertTrue(allocated < 32 * 1024, "allocated " + allocated);
    }
}
