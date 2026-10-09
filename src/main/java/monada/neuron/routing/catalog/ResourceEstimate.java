package monada.neuron.routing.catalog;

import java.util.Objects;

/**
 * Host-supplied annotation of one route's resource dimension inside a catalog snapshot.
 *
 * @param key route the estimate belongs to
 * @param dimension opaque dimension token (for example cost or latency)
 * @param unit opaque unit token; estimates are comparable only with equal dimension and unit
 * @param value known, unknown or not measured
 */
public record ResourceEstimate(RouteKey key, String dimension, String unit, ResourceValue value) {

    /** Validates tokens. */
    public ResourceEstimate {
        Objects.requireNonNull(key, "key must not be null");
        RouteTokens.require(dimension, "dimension");
        RouteTokens.require(unit, "unit");
        Objects.requireNonNull(value, "value must not be null");
    }
}
