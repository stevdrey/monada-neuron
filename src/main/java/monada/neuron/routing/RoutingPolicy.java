package monada.neuron.routing;

import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RoutingRequest;

/**
 * Versioned, explicit, rule-based route recommendation. Implementations are pure: equal inputs give equal decisions,
 * with no clock, randomness, I/O or retained state.
 */
public interface RoutingPolicy {

    /** Policy id recorded in provenance. */
    String policyId();

    /** Policy version recorded in provenance; any change of the ordering rules needs a new version. */
    String policyVersion();

    /**
     * Recommends a route for one stage.
     *
     * @param request host request with hard requirements
     * @param catalog immutable catalog snapshot
     * @param preference immutable preference snapshot (use {@link RoutingPreference#empty} for a cold start)
     * @return the decision; never reinterpreted as an action status
     */
    RoutingDecision decide(RoutingRequest request, RouteCatalog catalog, RoutingPreference preference);
}
