package monada.neuron.routing.comparison;

import monada.neuron.routing.catalog.RouteKey;

import java.util.Objects;

/**
 * One route whose standing differs between two comparable scenarios.
 *
 * @param route the route
 * @param before its standing in the before scenario
 * @param after its standing in the after scenario; never equal to {@code before}
 */
public record EligibilityChange(RouteKey route, RouteStanding before, RouteStanding after) {

    /** Requires non-null parts and a real change. */
    public EligibilityChange {
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (before.equals(after)) {
            throw new IllegalArgumentException("an eligibility change needs different standings, got " + before
                    + " for " + route);
        }
        if (before instanceof RouteStanding.Excluded(var exclusion) && !exclusion.key().equals(route)
                || after instanceof RouteStanding.Excluded(var exclusion2) && !exclusion2.key().equals(route)) {
            throw new IllegalArgumentException("an exclusion of another route cannot describe " + route);
        }
    }
}
