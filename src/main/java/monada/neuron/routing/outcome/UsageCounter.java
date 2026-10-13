package monada.neuron.routing.outcome;

import monada.neuron.routing.catalog.ResourceValue;

import java.util.Objects;

/**
 * One usage counter with provenance. A known {@code 0} is a real zero; unknown and not measured are never zero.
 *
 * @param kind counter kind
 * @param value known (reported or estimated), unknown or not measured
 */
public record UsageCounter(UsageKind kind, ResourceValue value) {

    /** Requires both parts. */
    public UsageCounter {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
