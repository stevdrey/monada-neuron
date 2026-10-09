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

import java.util.List;

/** Fixtures: the fictional catalog of Forge Routing Contract v1 section 9 (all identifiers are invented). */
final class RoutingFixtures {

    static final RouteKey SUB_A = new RouteKey("sub-a", 1);
    static final RouteKey SUB_B = new RouteKey("sub-b", 1);
    static final RouteKey API_X = new RouteKey("api-x", 1);

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
}
