package monada.neuron.routing.catalog;

import monada.neuron.routing.features.TaskFeatures;

import java.util.List;

/** Shared fixtures: the fictional catalog of Forge Routing Contract v1 section 9. */
final class CatalogFixtures {

    static final List<String> STAGES = List.of("plan", "implement", "review", "qa");

    private CatalogFixtures() {
    }

    static RouteDescriptor.Builder base(String id, long version) {
        return RouteDescriptor.builder(id, version)
                .billingMode(BillingMode.SUBSCRIPTION)
                .stages(STAGES)
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .tools(List.of("edit", "test"))
                .contextCeiling(100_000);
    }

    static RouteDescriptor subA() {
        return base("sub-a", 1).capabilities(List.of("java")).build();
    }

    static RouteDescriptor subB() {
        return base("sub-b", 1).capabilities(List.of("java")).build();
    }

    static RouteDescriptor apiX() {
        return base("api-x", 1).billingMode(BillingMode.API_METERED).overflowClass(OverflowClass.OVERFLOW)
                .tier(2).capabilities(List.of("java", "long-context")).build();
    }

    static CatalogEntry available(RouteDescriptor descriptor) {
        return new CatalogEntry(descriptor, Availability.AVAILABLE, 1);
    }

    static RouteCatalog demoCatalog() {
        return RouteCatalog.of("cat-demo-7", List.of(available(subA()), available(subB()), available(apiX())));
    }

    static HardRequirements requiring(List<String> capabilities) {
        return new HardRequirements(capabilities, List.of(), List.of("hosted"), List.of("sandboxed"), 0L);
    }

    static RoutingRequest request(String stageKind, HardRequirements requirements, boolean overflowPermitted) {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope-demo", "task-17", "exec-1", "att-1",
                "s-" + stageKind, stageKind, 101L, "src-1", "ctx-1", "con-1", "forge-gates", "1",
                TaskFeatures.builder().stageKind(stageKind).build(), requirements, overflowPermitted, 50L);
    }

    static RoutingRequest request(String stageKind, List<String> capabilities) {
        return request(stageKind, requiring(capabilities), false);
    }
}
