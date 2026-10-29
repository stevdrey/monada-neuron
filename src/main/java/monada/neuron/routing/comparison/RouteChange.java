package monada.neuron.routing.comparison;

/**
 * How the selected route differs between two comparable scenarios. It names a hypothetical change of advice, never a
 * better or worse route: a different selection says nothing about how either route would perform.
 */
public enum RouteChange {
    /** Both decisions select the same route. */
    SAME_ROUTE,
    /** Both decisions select a route, and the routes differ. */
    DIFFERENT_ROUTE,
    /** Only the after decision selects a route. */
    NEWLY_SELECTED,
    /** Only the before decision selects a route. */
    NO_LONGER_SELECTED,
    /** Neither decision selects a route; each is an abstention or has no eligible route. */
    NOT_SELECTED_EITHER
}
