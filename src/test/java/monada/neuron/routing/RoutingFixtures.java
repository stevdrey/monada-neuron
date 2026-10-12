package monada.neuron.routing;

import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.BillingMode;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.OverflowClass;
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

/** Fixtures: the fictional catalog of Forge Routing Contract v1 section 9 (all identifiers are invented). */
final class RoutingFixtures {

    static final RouteKey SUB_A = new RouteKey("sub-a", 1);
    static final RouteKey SUB_B = new RouteKey("sub-b", 1);
    static final RouteKey API_X = new RouteKey("api-x", 1);

    /** The reference policy's parameters. */
    static final PolicyParameters PARAMETERS =
            new PolicyParameters(3, OptionalInt.empty(), Optional.empty(), "routing-state/1", "task-features/1");

    /** A cohort binding for the default request (bucket {@code UNKNOWN}) under the reference mapping and schema. */
    static final CohortBinding COLD_COHORT = new CohortBinding("UNKNOWN", "b/1", "routing-state/1", "task-features/1");

    private RoutingFixtures() {
    }

    static RouteDescriptor.Builder base(String id, long version) {
        return RouteDescriptor.builder(id, version)
                .billingMode(BillingMode.SUBSCRIPTION)
                .stages(List.of("plan", "implement", "review", "qa"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .tools(List.of("edit", "test"))
                .capabilities(List.of("java"))
                .contextCeiling(100_000);
    }

    static RouteDescriptor apiX() {
        return base("api-x", 1).billingMode(BillingMode.API_METERED).overflowClass(OverflowClass.OVERFLOW)
                .tier(2).capabilities(List.of("java", "long-context")).build();
    }

    static CatalogEntry entry(RouteDescriptor descriptor, int fallbackPriority) {
        return new CatalogEntry(descriptor, Availability.AVAILABLE, fallbackPriority);
    }

    static RouteCatalog demo() {
        return RouteCatalog.of("cat-demo-7", List.of(entry(base("sub-a", 1).build(), 1),
                entry(base("sub-b", 1).build(), 1), entry(apiX(), 1)));
    }

    static RouteCatalog demoWith(List<ResourceEstimate> estimates) {
        return new RouteCatalog("cat-demo-7", demo().entries(), estimates);
    }

    static ResourceEstimate cost(RouteKey key, long value, String unit) {
        return new ResourceEstimate(key, "cost", unit, new ResourceValue.Known(value, ResourceValue.Provenance.REPORTED));
    }

    static ResourceEstimate cost(RouteKey key, ResourceValue value) {
        return new ResourceEstimate(key, "cost", "usd-cents", value);
    }

    static RoutingRequest request(List<String> capabilities, boolean overflowPermitted) {
        return request(TaskFeatures.builder().stageKind("implement").build(), capabilities, overflowPermitted, 50L);
    }

    static RoutingRequest request(
            TaskFeatures features, List<String> capabilities, boolean overflowPermitted, long cutoff) {
        var requirements = new HardRequirements(
                capabilities, List.of(), List.of("hosted"), List.of("sandboxed"), 0L);
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope-demo", "task-17", "exec-1", "att-1",
                "s-impl", "implement", 101L, "src-1", "ctx-1", "con-1", "forge-gates", "1", features, requirements,
                overflowPermitted, cutoff);
    }

    /** Provenance of a {@code route-lex/1} decision over {@link #demo()} for the given request and preference. */
    static Provenance provenance(RoutingRequest request, RoutingPreference preference, StateValidation validation,
            PolicyParameters parameters) {
        return new Provenance(DecisionRef.of(request), "cat-demo-7", LexicographicRoutingPolicy.POLICY_ID,
                LexicographicRoutingPolicy.POLICY_VERSION, request.cutoff(), preference.binding(), validation,
                parameters);
    }

    static RoutingPreference empty(RoutingRequest request) {
        return RoutingPreference.empty(request, RoutingStateDefinition.reference(),
                LexicographicRoutingPolicy.POLICY_ID, LexicographicRoutingPolicy.POLICY_VERSION);
    }

    static RoutingPreference preference(RoutingRequest request, long processedCutoff, CohortPreference... cohorts) {
        return new RoutingPreference(request.scopeId(), request.features().schemaVersion(),
                request.evaluationPolicyId(), request.evaluationPolicyVersion(),
                RoutingStateDefinition.reference().mappingVersion(), LexicographicRoutingPolicy.POLICY_ID,
                LexicographicRoutingPolicy.POLICY_VERSION, processedCutoff, List.of(cohorts));
    }

    /** A cohort of the default request (stage {@code implement}, change size unknown, bucket {@code UNKNOWN}). */
    static CohortPreference cohort(RouteKey key, double value, int support) {
        return new CohortPreference("implement", CohortMapping.UNKNOWN_BUCKET, key, value, support);
    }

    /** The largest catalog: 32 routes, each with a known cost estimate. */
    static RouteCatalog maximumCatalog() {
        List<CatalogEntry> entries = new ArrayList<>();
        List<ResourceEstimate> estimates = new ArrayList<>();
        for (int i = 0; i < RouteCatalog.MAX_ROUTES; i++) {
            RouteKey key = new RouteKey(String.format("route-%02d", i), 1);
            entries.add(entry(base(key.routeId(), 1).build(), 1));
            estimates.add(cost(key, i, "usd-cents"));
        }
        return new RouteCatalog("cat-max", entries, estimates);
    }

    /** The largest snapshot: 256 cohorts, 32 live ones for {@link #maximumCatalog()} and 224 dormant ones. */
    static RoutingPreference maximumPreference() {
        List<CohortPreference> cohorts = new ArrayList<>();
        for (int i = 0; i < RoutingPreference.MAX_COHORTS; i++) {
            RouteKey key = i < RouteCatalog.MAX_ROUTES ? new RouteKey(String.format("route-%02d", i), 1)
                    : new RouteKey("dormant-" + i, 1);
            cohorts.add(new CohortPreference("implement", "UNKNOWN", key, i % 7, 3));
        }
        return new RoutingPreference("scope-demo", "task-features/1", "forge-gates", "1", "routing-state/1",
                "route-lex", "1", 40, cohorts);
    }
}
