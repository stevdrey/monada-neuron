package monada.neuron.routing.catalog;

/**
 * Identity of one route: caller-issued {@code RouteId} and caller-assigned positive {@code RouteVersion}.
 *
 * <p>The natural order is the canonical route order of the contract: {@code RouteId} by code point, then the
 * version numerically.
 *
 * @param routeId opaque route token
 * @param routeVersion positive version, at least 1
 */
public record RouteKey(String routeId, long routeVersion) implements Comparable<RouteKey> {

    /** Validates the token and the version. */
    public RouteKey {
        RouteTokens.require(routeId, "routeId");
        if (routeVersion < 1) {
            throw new IllegalArgumentException("routeVersion must be at least 1, got: " + routeVersion);
        }
    }

    @Override
    public int compareTo(RouteKey other) {
        int byId = RouteTokens.CODE_POINT_ORDER.compare(routeId, other.routeId);
        return byId != 0 ? byId : Long.compare(routeVersion, other.routeVersion);
    }
}
