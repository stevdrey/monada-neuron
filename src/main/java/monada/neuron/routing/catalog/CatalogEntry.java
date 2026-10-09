package monada.neuron.routing.catalog;

import java.util.Objects;

/**
 * One route inside a catalog snapshot: the versioned descriptor plus snapshot-only values.
 *
 * <p>Availability and {@code fallbackPriority} change without a new {@code RouteVersion} (contract section 2).
 *
 * @param descriptor versioned behavioral descriptor
 * @param availability transient availability in this snapshot
 * @param fallbackPriority host ordering value, lower first; consumed by ranking (#62), not by eligibility
 */
public record CatalogEntry(RouteDescriptor descriptor, Availability availability, int fallbackPriority) {

    /** Requires non-null parts. */
    public CatalogEntry {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(availability, "availability must not be null");
    }

    /** The route identity. */
    public RouteKey key() {
        return descriptor.key();
    }
}
