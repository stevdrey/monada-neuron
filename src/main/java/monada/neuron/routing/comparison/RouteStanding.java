package monada.neuron.routing.comparison;

import monada.neuron.routing.catalog.EligibilityReport;

import java.util.Objects;

/**
 * Where one route stood in one scenario, from that scenario's catalog and its decision's recorded exclusions: exactly
 * one of {@link Eligible}, {@link Excluded} or {@link NotInCatalog}.
 */
public sealed interface RouteStanding permits RouteStanding.Eligible, RouteStanding.Excluded, RouteStanding.NotInCatalog {

    /** The route is in the catalog and the decision does not exclude it. */
    record Eligible() implements RouteStanding {
    }

    /**
     * The decision excludes the route.
     *
     * @param exclusion the decision's exclusion, unchanged
     */
    record Excluded(EligibilityReport.Exclusion exclusion) implements RouteStanding {

        /** Requires a non-null exclusion. */
        public Excluded {
            Objects.requireNonNull(exclusion, "exclusion must not be null");
        }
    }

    /** The scenario's catalog does not list the route. */
    record NotInCatalog() implements RouteStanding {
    }
}
