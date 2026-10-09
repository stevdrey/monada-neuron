package consumer;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.host.NeuronRuntime;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.routing.LexicographicRoutingPolicy;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingPreference;
import monada.neuron.routing.catalog.Availability;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.HardRequirements;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteDescriptor;
import monada.neuron.routing.catalog.RoutingRequest;
import monada.neuron.routing.features.TaskFeatures;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.List;
import java.util.UUID;

/** Minimal host that embeds Neuron through the public host API only. */
public final class ConsumerSmoke {

    private ConsumerSmoke() {
    }

    public static CognitiveCycleResult runOneCycle() {
        var runtime = NeuronRuntime.builder()
                .monad(new PrimaryMonad(new UUID(0L, 1L)))
                .defaultBudget(new CognitiveBudget(100, 100, 100))
                .build();
        var input = new Signal(SignalKind.INTERMEDIATE, new FrequencyState(1.0, 10.0, 0.0));
        return runtime.execute(List.of(input));
    }

    /** Level A routing: a pure, opt-in decision over invented routes (no provider, credential or Store type). */
    public static RoutingDecision routeOneStage() {
        var route = RouteDescriptor.builder("route-a", 1)
                .stages(List.of("implement"))
                .executionModes(List.of("sandboxed"))
                .locality("hosted")
                .build();
        var catalog = RouteCatalog.of("catalog-1", List.of(new CatalogEntry(route, Availability.AVAILABLE, 1)));
        var request = new RoutingRequest(RoutingRequest.CONTRACT_VERSION, "scope", "task", "exec", "attempt",
                "stage", "implement", 1L, "src", "ctx", "con", "eval", "1",
                TaskFeatures.builder().stageKind("implement").build(),
                new HardRequirements(List.of(), List.of(), List.of("hosted"), List.of("sandboxed"), 0L), false, 0L);
        var policy = LexicographicRoutingPolicy.reference();
        var preference = RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion());
        return policy.decide(request, catalog, preference);
    }
}
