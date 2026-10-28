package monada.neuron.routing.comparison;

import monada.neuron.routing.catalog.RouteCatalog;

import java.util.List;
import java.util.Objects;

/**
 * The canonical difference between the decisions of two comparable scenarios. It is derived from the two decisions,
 * catalogs and verified eligibility reports only; it ranks nothing again and holds no estimate, so it can state what advice would change, never whether
 * the change would help.
 *
 * @param routeChange how the selected route differs
 * @param decisionChanged whether the two decisions differ in any recorded field
 * @param rankingChanged whether the ranked candidate routes differ in membership, order or truncation (whether more
 *         eligible routes were ranked than listed)
 * @param cohortChanged whether the recorded cohort bindings differ, absence included
 * @param eligibilityChanges every route whose standing differs, in canonical route order, at most
 *         {@value #MAX_ELIGIBILITY_CHANGES}
 */
public record ScenarioDelta(
        RouteChange routeChange,
        boolean decisionChanged,
        boolean rankingChanged,
        boolean cohortChanged,
        List<EligibilityChange> eligibilityChanges) {

    /** Two catalogs of at most {@link RouteCatalog#MAX_ROUTES} routes each. */
    public static final int MAX_ELIGIBILITY_CHANGES = 2 * RouteCatalog.MAX_ROUTES;

    /** Validates bounds and canonical order and copies. */
    public ScenarioDelta {
        Objects.requireNonNull(routeChange, "routeChange must not be null");
        Objects.requireNonNull(eligibilityChanges, "eligibilityChanges must not be null");
        if (eligibilityChanges.size() > MAX_ELIGIBILITY_CHANGES) {
            throw new IllegalArgumentException("at most " + MAX_ELIGIBILITY_CHANGES + " eligibility changes, got: "
                    + eligibilityChanges.size());
        }
        eligibilityChanges = List.copyOf(eligibilityChanges);
        for (int i = 1; i < eligibilityChanges.size(); i++) {
            if (eligibilityChanges.get(i - 1).route().compareTo(eligibilityChanges.get(i).route()) >= 0) {
                throw new IllegalArgumentException("eligibility changes must be unique and in canonical route order");
            }
        }
        if (!decisionChanged && (routeChange == RouteChange.DIFFERENT_ROUTE || routeChange == RouteChange.NEWLY_SELECTED
                || routeChange == RouteChange.NO_LONGER_SELECTED || rankingChanged || cohortChanged)) {
            throw new IllegalArgumentException("equal decisions cannot differ in route, ranking or cohort");
        }
    }
}
