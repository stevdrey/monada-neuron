package monada.neuron.routing.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Comparator;

/**
 * Immutable, bounded snapshot of caller-supplied routes.
 *
 * <p>Entries are stored in canonical route order, so input permutations yield equal snapshots. Construction rejects
 * duplicate {@link RouteKey}s regardless of any other field, more than {@value #MAX_ROUTES} routes, more than
 * {@value #MAX_ESTIMATES_PER_ROUTE} estimates per route, duplicate estimates per (route, dimension) and estimates
 * for unknown routes. The {@code catalogVersion} must change whenever any content changes; Neuron stores no catalogs.
 *
 * @param catalogVersion opaque immutable identity of this snapshot's content
 * @param entries routes in canonical order
 * @param estimates resource estimates ordered by route, then dimension
 */
public record RouteCatalog(String catalogVersion, List<CatalogEntry> entries, List<ResourceEstimate> estimates) {

    /** Maximum routes per catalog. */
    public static final int MAX_ROUTES = 32;

    /** Maximum estimates (dimensions) per route. */
    public static final int MAX_ESTIMATES_PER_ROUTE = 4;

    /** Maximum estimates per catalog; checked before any copy or sort. */
    public static final int MAX_ESTIMATES = MAX_ROUTES * MAX_ESTIMATES_PER_ROUTE;

    /** Validates bounds and uniqueness and canonicalizes order. */
    public RouteCatalog {
        RouteTokens.require(catalogVersion, "catalogVersion");
        Objects.requireNonNull(entries, "entries must not be null");
        Objects.requireNonNull(estimates, "estimates must not be null");
        if (estimates.size() > MAX_ESTIMATES) {
            throw new IllegalArgumentException(
                    "catalog allows at most " + MAX_ESTIMATES + " estimates, got: " + estimates.size());
        }
        if (entries.size() > MAX_ROUTES) {
            throw new IllegalArgumentException(
                    "catalog allows at most " + MAX_ROUTES + " routes, got: " + entries.size());
        }
        List<CatalogEntry> sorted = new ArrayList<>(entries);
        for (CatalogEntry entry : sorted) {
            Objects.requireNonNull(entry, "entry must not be null");
        }
        sorted.sort(Comparator.comparing(CatalogEntry::key));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).key().equals(sorted.get(i).key())) {
                throw new IllegalArgumentException("duplicate route identity: " + sorted.get(i).key());
            }
        }
        List<ResourceEstimate> sortedEstimates = new ArrayList<>(estimates);
        for (ResourceEstimate estimate : sortedEstimates) {
            Objects.requireNonNull(estimate, "estimate must not be null");
        }
        sortedEstimates.sort(Comparator.comparing(ResourceEstimate::key)
                .thenComparing(ResourceEstimate::dimension, RouteTokens.CODE_POINT_ORDER));
        HashSet<RouteKey> known = HashSet.newHashSet(sorted.size());
        sorted.forEach(entry -> known.add(entry.key()));
        int perRoute = 0;
        for (int i = 0; i < sortedEstimates.size(); i++) {
            ResourceEstimate current = sortedEstimates.get(i);
            if (!known.contains(current.key())) {
                throw new IllegalArgumentException("estimate for a route not in the catalog: " + current.key());
            }
            ResourceEstimate previous = i == 0 ? null : sortedEstimates.get(i - 1);
            if (previous != null && previous.key().equals(current.key())) {
                if (previous.dimension().equals(current.dimension())) {
                    throw new IllegalArgumentException("duplicate estimate for " + current.key()
                            + " dimension: " + current.dimension());
                }
                perRoute++;
            } else {
                perRoute = 1;
            }
            if (perRoute > MAX_ESTIMATES_PER_ROUTE) {
                throw new IllegalArgumentException("at most " + MAX_ESTIMATES_PER_ROUTE
                        + " estimates per route, exceeded by: " + current.key());
            }
        }
        entries = List.copyOf(sorted);
        estimates = List.copyOf(sortedEstimates);
    }

    /** Creates a catalog without resource estimates. */
    public static RouteCatalog of(String catalogVersion, Collection<CatalogEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.size() > MAX_ROUTES) {
            throw new IllegalArgumentException(
                    "catalog allows at most " + MAX_ROUTES + " routes, got: " + entries.size());
        }
        return new RouteCatalog(catalogVersion, List.copyOf(entries), List.of());
    }

    /** Looks up an entry by identity; linear over at most {@value #MAX_ROUTES} routes. */
    public Optional<CatalogEntry> find(RouteKey key) {
        return entries.stream().filter(entry -> entry.key().equals(key)).findFirst();
    }
}
