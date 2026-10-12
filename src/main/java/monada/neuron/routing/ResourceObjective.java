package monada.neuron.routing;

import java.util.Objects;

/**
 * Policy parameter selecting which host-supplied resource dimension refines otherwise tied candidates.
 *
 * <p>It is a policy parameter, not a request field, so a request cannot steer it. Only {@code Known} estimates with
 * equal dimension and unit are comparable; the unit token must therefore carry currency and pricing assumptions,
 * since {@code ResourceEstimate} has no separate field for them.
 *
 * @param dimension dimension token to compare
 * @param direction whether the smaller or the larger known value wins
 */
public record ResourceObjective(String dimension, Direction direction) {

    /** Which end of the comparable range wins. */
    public enum Direction {
        /** Smaller known value first (for example cost or latency). */
        MINIMIZE,
        /** Larger known value first. */
        MAXIMIZE
    }

    /** Validates parts. */
    public ResourceObjective {
        RoutingTokens.require(dimension, "dimension");
        Objects.requireNonNull(direction, "direction must not be null");
    }
}
