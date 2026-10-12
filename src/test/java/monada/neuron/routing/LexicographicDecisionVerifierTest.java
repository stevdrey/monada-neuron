package monada.neuron.routing;

import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteEligibilityFilter;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static monada.neuron.routing.RoutingFixtures.API_X;
import static monada.neuron.routing.RoutingFixtures.COLD_COHORT;
import static monada.neuron.routing.RoutingFixtures.PARAMETERS;
import static monada.neuron.routing.RoutingFixtures.SUB_A;
import static monada.neuron.routing.RoutingFixtures.SUB_B;
import static monada.neuron.routing.RoutingFixtures.demo;
import static monada.neuron.routing.RoutingFixtures.empty;
import static monada.neuron.routing.RoutingFixtures.provenance;
import static monada.neuron.routing.RoutingFixtures.request;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code route-lex/1} verification: parameter-derived admission, exact ranking, truncation and the tier cap. */
class LexicographicDecisionVerifierTest {

    private static final List<RoutingRule> APPLIED =
            List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.ROUTE_ORDER);
    private static final List<RoutingRule> SKIPPED =
            List.of(RoutingRule.LEARNED_PREFERENCE, RoutingRule.RESOURCE_OBJECTIVE);

    private final LexicographicDecisionVerifier verifier = new LexicographicDecisionVerifier();
    private final RouteEligibilityFilter filter = new RouteEligibilityFilter();

    private void verify(RoutingDecision decision, RoutingInput input) {
        verifier.verify(decision, input, filter.evaluate(input.request(), input.catalog()));
    }

    private String rejected(RoutingDecision decision, RoutingInput input) {
        return assertThrows(IllegalStateException.class, () -> verify(decision, input)).getMessage();
    }

    private static List<RankedCandidate> inOrder(RouteKey... keys) {
        var ranked = new ArrayList<RankedCandidate>(keys.length);
        for (RouteKey key : keys) {
            ranked.add(new RankedCandidate(ranked.size(), key, keys.length == 1 ? Placement.ONLY_ELIGIBLE
                    : Placement.ROUTE_ORDER));
        }
        return ranked;
    }

    private static RoutingDecision.Selected selected(Provenance provenance, RouteKey route, String mode,
            boolean overflow, List<RankedCandidate> ranked, boolean truncated,
            List<EligibilityReport.Exclusion> exclusions) {
        return new RoutingDecision.Selected(provenance, route, mode, ranked.getFirst().placement().basis(), overflow,
                ranked, truncated, APPLIED, SKIPPED, exclusions, COLD_COHORT);
    }

    private static RoutingDecision.Abstain incompatible(RoutingRequest request, RoutingPreference preference,
            List<StateMismatch> reasons, PolicyParameters parameters) {
        return new RoutingDecision.Abstain(
                provenance(request, preference, new StateValidation.Incompatible(reasons), parameters),
                AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A, SUB_B), List.of(), false,
                new RouteEligibilityFilter().evaluate(request, demo()).excluded());
    }

    @Test
    void everyReferenceDecisionVariantIsAccepted() {
        var request = request(List.of("java"), false);
        var good = empty(request);
        var policy = LexicographicRoutingPolicy.reference();
        verify(assertInstanceOf(RoutingDecision.Selected.class, policy.decide(request, demo(), good)),
                new RoutingInput(request, demo(), good));

        // Only api-x (tier 2) is eligible, above the cap of 1.
        var capped = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(), 3, OptionalInt.of(1),
                Optional.empty());
        var overflow = request(List.of("long-context"), true);
        verify(assertInstanceOf(RoutingDecision.Abstain.class, capped.decide(overflow, demo(), empty(overflow))),
                new RoutingInput(overflow, demo(), empty(overflow)));

        var none = request(List.of("java", "long-context"), false);
        verify(assertInstanceOf(RoutingDecision.NoEligibleRoute.class, policy.decide(none, demo(), empty(none))),
                new RoutingInput(none, demo(), empty(none)));
    }

    @Test
    void provenanceAndEligibilityAreNotItsResponsibility() {
        var request = request(List.of("java"), false);
        var input = new RoutingInput(request, demo(), empty(request));
        var foreign = new Provenance(DecisionRef.of(request), "cat-other", "stub", "9", request.cutoff(),
                empty(request).binding(), new StateValidation.Compatible(), PARAMETERS);
        // Foreign identity, another execution mode and missing exclusions: only the mandatory validator rejects these.
        var decision = selected(foreign, SUB_A, "other-mode", false, inOrder(SUB_A, SUB_B), false, List.of());

        verify(decision, input);
        assertThrows(IllegalStateException.class, () -> new RoutingDecisionValidator().validate(decision, input,
                LexicographicRoutingPolicy.reference(), filter.evaluate(request, demo())));
    }

    @Test
    void parameterDerivedMismatchesMustBeReportedExactly() {
        var request = request(List.of("java"), false);
        var good = empty(request);
        var input = new RoutingInput(request, demo(), good);
        var otherMapping = new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "other-mapping",
                "task-features/1");
        var otherSchema = new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "routing-state/1",
                "task-features/0");

        verify(incompatible(request, good, List.of(StateMismatch.MAPPING_VERSION_MISMATCH), otherMapping), input);
        verify(incompatible(request, good, List.of(StateMismatch.FEATURE_SCHEMA_MISMATCH), otherSchema), input);
        assertTrue(rejected(incompatible(request, good, List.of(StateMismatch.SCOPE_MISMATCH), otherMapping), input)
                .contains("omits state mismatches the recorded policy parameters imply"));
        assertTrue(rejected(incompatible(request, good, List.of(StateMismatch.SCOPE_MISMATCH), otherSchema), input)
                .contains("omits"));
        for (StateMismatch spurious : StateMismatch.values()) {
            assertTrue(rejected(incompatible(request, good, List.of(spurious), PARAMETERS), input)
                    .contains("does not exist"), spurious.toString());
        }

        // A request-derived reason that really exists is not spurious.
        var foreignScope = new RoutingPreference("other-scope", good.featureSchemaVersion(), good.evaluationPolicyId(),
                good.evaluationPolicyVersion(), good.mappingVersion(), "p", "1", 0, List.of());
        verify(incompatible(request, foreignScope, List.of(StateMismatch.SCOPE_MISMATCH), PARAMETERS),
                new RoutingInput(request, demo(), foreignScope));

        // A compatible decision whose recorded mapping was built for another feature schema.
        var oldSchema = new RoutingPreference(good.scopeId(), "task-features/0", good.evaluationPolicyId(),
                good.evaluationPolicyVersion(), good.mappingVersion(), "p", "1", 0, List.of());
        var compatible = new RoutingDecision.Selected(
                provenance(request, oldSchema, new StateValidation.Compatible(), otherSchema), SUB_A, "sandboxed",
                Basis.COLD_START, false, inOrder(SUB_A, SUB_B), false, APPLIED, SKIPPED, List.of(),
                new CohortBinding("UNKNOWN", "b/1", "routing-state/1", "task-features/0"));
        assertTrue(rejected(compatible, new RoutingInput(request, demo(), oldSchema)).contains("policy mapping"));
    }

    @Test
    void rankingsAndTruncationMustMatchTheRecomputedOrdering() {
        var request = request(List.of("java"), false);
        var input = new RoutingInput(request, demo(), empty(request));
        var compatible = provenance(request, empty(request), new StateValidation.Compatible(), PARAMETERS);

        verify(selected(compatible, SUB_A, "sandboxed", false, inOrder(SUB_A, SUB_B), false, List.of()), input);
        assertTrue(rejected(selected(compatible, SUB_B, "sandboxed", false, inOrder(SUB_B, SUB_A), false, List.of()),
                input).contains("recomputed ranking"));
        assertTrue(rejected(selected(compatible, SUB_A, "sandboxed", false, inOrder(SUB_A), false, List.of()), input)
                .contains("recomputed ranking"));

        // Twelve equal routes: the first eight in canonical order are right, but the truncation flag must be set.
        var entries = new ArrayList<CatalogEntry>();
        var firstEight = new RouteKey[RoutingDecision.MAX_CANDIDATES];
        for (int i = 0; i < 12; i++) {
            entries.add(RoutingFixtures.entry(RoutingFixtures.base("route-%02d".formatted(i), 1).build(), 1));
            if (i < firstEight.length) {
                firstEight[i] = new RouteKey("route-%02d".formatted(i), 1);
            }
        }
        var twelve = new RoutingInput(request, RouteCatalog.of("cat-twelve", entries), empty(request));
        verify(selected(compatible, firstEight[0], "sandboxed", false, inOrder(firstEight), true, List.of()), twelve);
        assertTrue(rejected(selected(compatible, firstEight[0], "sandboxed", false, inOrder(firstEight), false,
                List.of()), twelve).contains("recomputed ranking"));
    }

    @Test
    void theTierCapIsRecomputedFromTheRecordedParameters() {
        // Only api-x (tier 2) is eligible.
        var request = request(List.of("long-context"), true);
        var input = new RoutingInput(request, demo(), empty(request));
        var only = inOrder(API_X);

        for (int cap : new int[] {1, 2}) {
            var parameters = new PolicyParameters(3, OptionalInt.of(cap), Optional.empty(), "routing-state/1",
                    "task-features/1");
            var compatible = provenance(request, empty(request), new StateValidation.Compatible(), parameters);
            var select = selected(compatible, API_X, "sandboxed", true, only, false, List.of());
            var abstain = new RoutingDecision.Abstain(compatible, AbstainReason.POLICY_TRADEOFF_UNRESOLVED,
                    List.of(API_X), only, false, List.of(), Optional.of(COLD_COHORT));
            if (cap == 1) {
                verify(abstain, input);
                assertTrue(rejected(select, input).contains("above the recorded tier cap"));
            } else {
                verify(select, input);
                assertTrue(rejected(abstain, input).contains("does not exceed"));
            }
        }
    }
}
