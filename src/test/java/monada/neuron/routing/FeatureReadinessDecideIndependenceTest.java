package monada.neuron.routing;

import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.FeatureDimension;
import monada.neuron.routing.features.FeatureReadinessAssessment;
import monada.neuron.routing.features.TaskFeatureReadiness;
import monada.neuron.routing.features.TaskFeatures;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FeatureReadinessDecideIndependenceTest {

    private final LexicographicRoutingPolicy policy = LexicographicRoutingPolicy.reference();

    private static RouteCatalog catalog() {
        var route = RouteDescriptor.builder("route-a", 1)
                .stages(List.of("implement"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .build();
        return RouteCatalog.of("catalog-1", List.of(new CatalogEntry(route, Availability.AVAILABLE, 1)));
    }

    private static RoutingRequest request(TaskFeatures features) {
        return new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope", "task", "exec", "attempt",
                "stage", "implement", 1L, "src", "ctx", "con", "eval", "1", features,
                new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false, 0L);
    }

    private RoutingDecision decide(RoutingRequest request) {
        return policy.decide(request, catalog(),
                RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion()));
    }

    // covers: AC-8
    @Test
    void assessingTheFeaturesDoesNotChangeTheDecision() {
        for (TaskFeatures features : List.of(TaskFeatures.builder().stageKind("implement").build(),
                TaskFeatures.builder().build())) {
            RoutingRequest request = request(features);
            RoutingDecision before = decide(request);

            assertInstanceOf(FeatureReadinessAssessment.Assessed.class, new TaskFeatureReadiness()
                    .assess(features, request.stageKind(), EnumSet.allOf(FeatureDimension.class)));

            assertEquals(before, decide(request));
            assertEquals(before, decide(request(features)));
        }
    }
}
