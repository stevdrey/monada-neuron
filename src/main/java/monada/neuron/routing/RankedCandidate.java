package monada.neuron.routing;

import monada.neuron.routing.catalog.RouteKey;

import java.util.Objects;

/**
 * One eligible route in the ranked explanation.
 *
 * @param rank zero-based position
 * @param key route identity
 * @param placement rule that separated this route from its neighbour (the next one for rank 0, otherwise the
 *         previous one); {@link Placement#ONLY_ELIGIBLE} for a sole route
 */
public record RankedCandidate(int rank, RouteKey key, Placement placement) {

    /** Validates parts. */
    public RankedCandidate {
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be non-negative, got: " + rank);
        }
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(placement, "placement must not be null");
    }
}
