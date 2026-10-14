package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteEligibilityFilter;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static monada.neuron.routing.RoutingFixtures.APPLIED;
import static monada.neuron.routing.RoutingFixtures.COLD_COHORT;
import static monada.neuron.routing.RoutingFixtures.PARAMETERS;
import static monada.neuron.routing.RoutingFixtures.SKIPPED;
import static monada.neuron.routing.RoutingFixtures.SUB_A;
import static monada.neuron.routing.RoutingFixtures.SUB_B;
import static monada.neuron.routing.RoutingFixtures.demo;
import static monada.neuron.routing.RoutingFixtures.empty;
import static monada.neuron.routing.RoutingFixtures.provenance;
import static monada.neuron.routing.RoutingFixtures.request;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mandatory, policy-independent validation: provenance, request-derived admission and hard eligibility. */
class RoutingDecisionValidatorTest {

    private final RoutingDecisionValidator validator = new RoutingDecisionValidator();
    private final RouteEligibilityFilter filter = new RouteEligibilityFilter();
    private final LexicographicRoutingPolicy policy = LexicographicRoutingPolicy.reference();

    private static RoutingInput input(RoutingRequest request, RoutingPreference preference) {
        return new RoutingInput(request, demo(), preference);
    }

    private EligibilityReport report(RoutingInput input) {
        return filter.evaluate(input.request(), input.catalog());
    }

    private void validate(RoutingDecision decision, RoutingInput input) {
        validator.validate(decision, input, policy, report(input));
    }

    private String rejected(RoutingDecision decision, RoutingInput input) {
        return assertThrows(IllegalStateException.class, () -> validate(decision, input)).getMessage();
    }

    private static RoutingPreference foreignScope(RoutingRequest request) {
        var good = empty(request);
        return new RoutingPreference("other-scope", good.featureSchemaVersion(), good.evaluationPolicyId(),
                good.evaluationPolicyVersion(), good.mappingVersion(), "p", "1", 0, List.of());
    }

    private static RoutingDecision.Selected selected(Provenance provenance, RouteKey route, String mode,
            boolean overflow, List<RankedCandidate> ranked, List<EligibilityReport.Exclusion> exclusions) {
        return new RoutingDecision.Selected(provenance, route, mode, Basis.COLD_START, overflow, ranked, false, APPLIED,
                SKIPPED, exclusions, COLD_COHORT);
    }

    private static List<RankedCandidate> inOrder(RouteKey first, RouteKey second) {
        return List.of(new RankedCandidate(0, first, Placement.ROUTE_ORDER),
                new RankedCandidate(1, second, Placement.ROUTE_ORDER));
    }

    @Test
    void everyReferenceDecisionVariantIsAccepted() {
        var request = request(List.of("java"), false);
        var selectedInput = input(request, empty(request));
        validate(assertInstanceOf(RoutingDecision.Selected.class, policy.decide(request, demo(), empty(request))),
                selectedInput);

        var incompatibleInput = input(request, foreignScope(request));
        validate(assertInstanceOf(RoutingDecision.Abstain.class,
                policy.decide(request, demo(), foreignScope(request))), incompatibleInput);

        // Only api-x (tier 2) is eligible, above the cap of 1.
        var capped = new LexicographicRoutingPolicy(RoutingStateDefinition.reference(), 3, OptionalInt.of(1),
                Optional.empty());
        var overflow = request(List.of("long-context"), true);
        var overflowInput = input(overflow, empty(overflow));
        var tradeoff = assertInstanceOf(RoutingDecision.Abstain.class,
                capped.decide(overflow, demo(), empty(overflow)));
        validator.validate(tradeoff, overflowInput, capped, report(overflowInput));

        var none = request(List.of("java", "long-context"), false);
        validate(assertInstanceOf(RoutingDecision.NoEligibleRoute.class, policy.decide(none, demo(), empty(none))),
                input(none, empty(none)));
    }

    @Test
    void orderingAndParameterDerivedAdmissionAreNotItsResponsibility() {
        var request = request(List.of("java"), false);
        var input = input(request, empty(request));
        var excluded = report(input).excluded();

        var verifier = new SelfReportedLexicographicVerifier();

        // A reversed ranking passes the mandatory validation and is rejected only by the route-lex/1 verifier.
        var reversed = selected(provenance(request, empty(request), new StateValidation.Compatible(), PARAMETERS),
                SUB_B, "sandboxed", false, inOrder(SUB_B, SUB_A), excluded);
        validate(reversed, input);
        assertTrue(assertThrows(IllegalStateException.class, () -> verifier.verify(reversed, input, report(input)))
                .getMessage().contains("recomputed ranking"));

        // Omitting one of two mismatches only the recorded parameters imply (another schema and another mapping),
        // with no spurious reason, is left to that verifier.
        var otherDefinition = new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "other-mapping",
                "task-features/0");
        var omitting = new RoutingDecision.Abstain(provenance(request, empty(request),
                new StateValidation.Incompatible(List.of(StateMismatch.FEATURE_SCHEMA_MISMATCH)), otherDefinition),
                AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A, SUB_B), List.of(), false, excluded);
        validate(omitting, input);
        assertTrue(assertThrows(IllegalStateException.class, () -> verifier.verify(omitting, input, report(input)))
                .getMessage().contains("omits state mismatches the recorded policy parameters imply"));

        // A spurious reason with nothing omitted: neither the request nor the parameters imply a scope mismatch.
        var spurious = new RoutingDecision.Abstain(provenance(request, empty(request),
                new StateValidation.Incompatible(List.of(StateMismatch.SCOPE_MISMATCH)), PARAMETERS),
                AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A, SUB_B), List.of(), false, excluded);
        validate(spurious, input);
        assertTrue(assertThrows(IllegalStateException.class, () -> verifier.verify(spurious, input, report(input)))
                .getMessage().contains("does not exist"));
    }

    @Test
    void aReportForAnotherCatalogIsRejected() {
        var request = request(List.of("java"), false);
        var input = input(request, empty(request));
        var decision = policy.decide(request, demo(), empty(request));
        var foreign = filter.evaluate(request, RouteCatalog.of("cat-other", demo().entries()));

        var failure = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(decision, input, policy, foreign));
        assertTrue(failure.getMessage().contains("cat-other"));
    }

    @Test
    void foreignProvenanceIsRejected() {
        var request = request(List.of("java"), false);
        var input = input(request, empty(request));
        var state = empty(request).binding();
        var compatible = new StateValidation.Compatible();
        var excluded = report(input).excluded();
        var ref = DecisionRef.of(request);
        var otherState = new StateBinding("scope-demo", "task-features/1", "forge-gates", "1", "routing-state/1", 9,
                "stub", "1");

        record Case(Provenance provenance, String message) { }
        for (Case c : List.of(
                new Case(new Provenance(ref, "cat-other", "route-lex", "1", request.cutoff(), state, compatible,
                        PARAMETERS), "cat-other"),
                new Case(new Provenance(new DecisionRef("scope-demo", "other-task", "exec-1", "att-1", "s-impl", 101L),
                        "cat-demo-7", "route-lex", "1", request.cutoff(), state, compatible, PARAMETERS),
                        "another request"),
                new Case(new Provenance(ref, "cat-demo-7", "route-lex", "1", request.cutoff() + 1, state, compatible,
                        PARAMETERS), "cutoff"),
                new Case(new Provenance(ref, "cat-demo-7", "route-lex", "1", request.cutoff(), otherState, compatible,
                        PARAMETERS), "preference snapshot"),
                new Case(new Provenance(ref, "cat-demo-7", "other-policy", "1", request.cutoff(), state, compatible,
                        PARAMETERS), "other-policy"),
                new Case(new Provenance(ref, "cat-demo-7", "route-lex", "2", request.cutoff(), state, compatible,
                        PARAMETERS), "route-lex/2"),
                new Case(new Provenance(ref, "cat-demo-7", "route-lex", "1", request.cutoff(), state, compatible,
                        PARAMETERS, Optional.of("review")), "stage kind review"))) {
            var decision = selected(c.provenance(), SUB_A, "sandboxed", false, inOrder(SUB_A, SUB_B), excluded);
            assertTrue(rejected(decision, input).contains(c.message()), c.toString());
        }
    }

    @Test
    void requestDerivedStateMismatchesMustBeReported() {
        var request = request(List.of("java"), false);
        var foreign = foreignScope(request);
        var input = input(request, foreign);
        var excluded = report(input).excluded();

        var compatible = selected(provenance(request, foreign, new StateValidation.Compatible(), PARAMETERS), SUB_A,
                "sandboxed", false, inOrder(SUB_A, SUB_B), excluded);
        assertTrue(rejected(compatible, input).contains("conflicts with the request"));

        var omitting = new RoutingDecision.Abstain(provenance(request, foreign,
                new StateValidation.Incompatible(List.of(StateMismatch.MAPPING_VERSION_MISMATCH)), PARAMETERS),
                AbstainReason.STATE_INCOMPATIBLE, List.of(SUB_A, SUB_B), List.of(), false, excluded);
        assertTrue(rejected(omitting, input).contains("omits state mismatches the request implies"));
    }

    @Test
    void decisionsThatDisagreeWithTheEligibilityFilterAreRejected() {
        var request = request(List.of("java"), false);
        var input = input(request, empty(request));
        var excluded = report(input).excluded();
        var compatible = provenance(request, empty(request), new StateValidation.Compatible(), PARAMETERS);

        // api-x is an overflow route and the host did not permit overflow; a valid record cannot list it as excluded.
        assertTrue(rejected(selected(compatible, RoutingFixtures.API_X, "sandboxed", true,
                inOrder(RoutingFixtures.API_X, SUB_A), List.of()), input).contains("not eligible"));
        assertTrue(rejected(selected(compatible, SUB_A, "other-mode", false, inOrder(SUB_A, SUB_B), excluded), input)
                .contains("disagrees"));
        assertTrue(rejected(selected(compatible, SUB_A, "sandboxed", true, inOrder(SUB_A, SUB_B), excluded), input)
                .contains("disagrees"));
        assertTrue(rejected(selected(compatible, SUB_A, "sandboxed", false, inOrder(SUB_A, SUB_B), List.of()), input)
                .contains("exclusions disagree"));

        var notEvaluated = provenance(request, empty(request), new StateValidation.NotEvaluated(), PARAMETERS);
        assertTrue(rejected(new RoutingDecision.NoEligibleRoute(notEvaluated, List.of()), input)
                .contains("no eligible route"));

        var foreign = foreignScope(request);
        var incompatible = provenance(request, foreign,
                new StateValidation.Incompatible(List.of(StateMismatch.SCOPE_MISMATCH)), PARAMETERS);
        assertTrue(rejected(new RoutingDecision.Abstain(incompatible, AbstainReason.STATE_INCOMPATIBLE,
                List.of(SUB_A), List.of(), false, excluded), input(request, foreign))
                .contains("other eligible routes"));
    }
}
