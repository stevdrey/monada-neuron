package monada.neuron.routing;

import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;

import java.util.Objects;

/**
 * The per-execution inputs of one routing decision, as resolved by the host.
 *
 * @param request stage request
 * @param catalog catalog snapshot
 * @param preference preference snapshot
 */
public record RoutingInput(RoutingRequest request, RouteCatalog catalog, RoutingPreference preference) {

    /** Requires non-null parts. */
    public RoutingInput {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(preference, "preference must not be null");
    }
}
