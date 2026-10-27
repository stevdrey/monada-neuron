package monada.neuron.routing.review;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.Placement;
import monada.neuron.routing.PolicyParameters;
import monada.neuron.routing.Provenance;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.ResourceObjective;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.RoutingRule;
import monada.neuron.routing.RoutingStateDefinition;
import monada.neuron.routing.StateBinding;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.StateValidation;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.ResourceEstimate;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.TaskFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Hand built decisions from the public routing records, so review tests control every field (identifiers invented). */
final class ReviewFixtures {

    static final RouteKey A = route(0);
    static final RouteKey B = route(1);
    static final RouteKey C = route(2);
    static final RouteKey D = route(3);

    static final DecisionRef REF = new DecisionRef("scope-demo", "task-17", "exec-1", "att-1", "s-impl", 101L);

    static final StateBinding STATE = new StateBinding("scope-demo", "task-features/1", "forge-gates", "1",
            "routing-state/1", 0L, "route-lex", "1");

    static final PolicyParameters PARAMETERS =
            new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "routing-state/1", "task-features/1");

    static final PolicyParameters WITH_OBJECTIVE = new PolicyParameters(3, OptionalInt.empty(),
            Optional.of(new ResourceObjective("cost", ResourceObjective.Direction.MINIMIZE)), "routing-state/1",
            "task-features/1");

    static final CohortBinding COHORT = new CohortBinding("UNKNOWN", "b/1", "routing-state/1", "task-features/1");

    /** The rules a cold start ordering applies. */
    static final List<RoutingRule> COLD_APPLIED =
            List.of(RoutingRule.TIER, RoutingRule.FALLBACK_PRIORITY, RoutingRule.ROUTE_ORDER);

    /** The optional rules a cold start ordering skips. */
    static final List<RoutingRule> COLD_SKIPPED =
            List.of(RoutingRule.LEARNED_PREFERENCE, RoutingRule.RESOURCE_OBJECTIVE);

    private ReviewFixtures() {
    }

    static RouteKey route(int i) {
        return new RouteKey(String.format("route-%02d", i), 1);
    }

    static List<RouteKey> routes(int from, int count) {
        List<RouteKey> keys = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            keys.add(route(i));
        }
        return keys;
    }

    static EligibilityReport.Exclusion exclusion(RouteKey key, EligibilityReason primary,
            EligibilityReason... additional) {
        return new EligibilityReport.Exclusion(key, primary, List.of(additional));
    }

    static Provenance provenance(StateValidation validation) {
        return provenance(validation, PARAMETERS);
    }

    static Provenance provenance(StateValidation validation, PolicyParameters parameters) {
        return new Provenance(REF, "cat-demo-7", "route-lex", "1", 50L, STATE, validation, parameters,
                Optional.of("implement"));
    }

    /** A provenance written before the stage kind was recorded. */
    static Provenance provenanceWithoutStageKind(StateValidation validation) {
        return new Provenance(REF, "cat-demo-7", "route-lex", "1", 50L, STATE, validation, PARAMETERS);
    }

    static StateValidation.Incompatible incompatible(StateMismatch... reasons) {
        return new StateValidation.Incompatible(List.of(reasons));
    }

    /** Ranks {@code keys} in the given order, every candidate placed by {@code placement}. */
    static List<RankedCandidate> ranked(List<RouteKey> keys, Placement placement) {
        List<RankedCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            candidates.add(new RankedCandidate(i, keys.get(i), placement));
        }
        return candidates;
    }

    /** A cold start selection of the first route, tier separating the leaders, untruncated, no overflow. */
    static RoutingDecision.Selected selected(List<RouteKey> ranking, List<EligibilityReport.Exclusion> exclusions) {
        return selected(provenance(new StateValidation.Compatible()), ranking, Placement.TIER, false, false,
                COLD_APPLIED, COLD_SKIPPED, exclusions);
    }

    static RoutingDecision.Selected selected(Provenance provenance, List<RouteKey> ranking, Placement placement,
            boolean overflowUsed, boolean truncated, List<RoutingRule> applied, List<RoutingRule> skipped,
            List<EligibilityReport.Exclusion> exclusions) {
        return new RoutingDecision.Selected(provenance, ranking.getFirst(), "sandboxed", placement.basis(),
                overflowUsed, ranked(ranking, placement), truncated, applied, skipped, exclusions, COHORT);
    }

    /** A sole route selection: placement {@code ONLY_ELIGIBLE}, basis {@code COLD_START}. */
    static RoutingDecision.Selected sole(RouteKey key, List<EligibilityReport.Exclusion> exclusions) {
        return selected(provenance(new StateValidation.Compatible()), List.of(key), Placement.ONLY_ELIGIBLE, false,
                false, COLD_APPLIED, COLD_SKIPPED, exclusions);
    }

    static RoutingDecision.Abstain stateIncompatible(StateValidation.Incompatible validation, List<RouteKey> eligible,
            List<EligibilityReport.Exclusion> exclusions) {
        return new RoutingDecision.Abstain(provenance(validation), AbstainReason.STATE_INCOMPATIBLE, eligible,
                List.of(), false, exclusions);
    }

    /** A tier tradeoff abstention ranking the first {@code min(8, eligible)} routes in canonical order. */
    static RoutingDecision.Abstain tradeoff(List<RouteKey> eligible, List<EligibilityReport.Exclusion> exclusions) {
        List<RouteKey> sorted = eligible.stream().sorted().toList();
        int shown = Math.min(RoutingDecision.MAX_CANDIDATES, sorted.size());
        Placement placement = shown == 1 && sorted.size() == 1 ? Placement.ONLY_ELIGIBLE : Placement.TIER;
        return new RoutingDecision.Abstain(provenance(new StateValidation.Compatible()),
                AbstainReason.POLICY_TRADEOFF_UNRESOLVED, eligible, ranked(sorted.subList(0, shown), placement),
                sorted.size() > RoutingDecision.MAX_CANDIDATES, exclusions, Optional.of(COHORT));
    }

    static RoutingDecision.NoEligibleRoute noEligibleRoute(List<EligibilityReport.Exclusion> exclusions) {
        return new RoutingDecision.NoEligibleRoute(provenance(new StateValidation.NotEvaluated()), exclusions);
    }

    /** A request for stage {@code implement} at cutoff 50 that requires the given capabilities. */
    static RoutingRequest request(List<String> capabilities) {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope-demo", "task-17", "exec-1", "att-1",
                "s-impl", "implement", 101L, "src-1", "ctx-1", "con-1", "forge-gates", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(capabilities, List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false,
                50L);
    }

    /** An available route offering capability {@code java} for stage {@code implement}. */
    static CatalogEntry entry(RouteKey key, int tier, int fallbackPriority) {
        return new CatalogEntry(RouteDescriptor.builder(key.routeId(), key.routeVersion())
                .stages(List.of("implement"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .capabilities(List.of("java"))
                .tier(tier)
                .build(), Availability.AVAILABLE, fallbackPriority);
    }

    static ResourceEstimate cost(RouteKey key, ResourceValue value) {
        return new ResourceEstimate(key, "cost", "usd-cents", value);
    }

    static ResourceValue known(long value) {
        return new ResourceValue.Known(value, ResourceValue.Provenance.REPORTED);
    }

    /** A policy like the reference one but minimizing the {@code cost} estimate. */
    static LexicographicRoutingPolicy costPolicy() {
        return new LexicographicRoutingPolicy(RoutingStateDefinition.reference(),
                LexicographicRoutingPolicy.DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.empty(),
                Optional.of(new ResourceObjective("cost", ResourceObjective.Direction.MINIMIZE)));
    }

    /** Decides with a cold start snapshot for {@code request}. */
    static RoutingDecision decide(LexicographicRoutingPolicy policy, RoutingRequest request, RouteCatalog catalog) {
        return policy.decide(request, catalog, RoutingPreference.empty(request, policy.definition(),
                policy.policyId(), policy.policyVersion()));
    }
}
