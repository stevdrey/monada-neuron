package monada.neuron.routing.comparison;

import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The canonical difference between the decisions of two comparable scenarios. It is derived from the two decisions and
 * catalogs only; it ranks nothing again and holds no estimate, so it can state what advice would change, never whether
 * the change would help.
 *
 * @param routeChange how the selected route differs
 * @param decisionChanged whether the two decisions differ in any recorded field
 * @param rankingChanged whether the ranked candidate routes differ in membership or order
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

    /**
     * The delta between two decisions: every field but the eligibility changes is derived from the decisions alone, so
     * {@link ScenarioComparison.Compared} can check a delta against the decisions it describes.
     */
    static ScenarioDelta between(
            RoutingDecision before, RoutingDecision after, List<EligibilityChange> eligibilityChanges) {
        return new ScenarioDelta(
                routeChange(selected(before), selected(after)),
                !before.equals(after),
                !rankedKeys(before).equals(rankedKeys(after)),
                !cohort(before).equals(cohort(after)),
                eligibilityChanges);
    }

    private static RouteChange routeChange(Optional<RouteKey> before, Optional<RouteKey> after) {
        if (before.isPresent() && after.isPresent()) {
            return before.equals(after) ? RouteChange.SAME_ROUTE : RouteChange.DIFFERENT_ROUTE;
        }
        if (after.isPresent()) {
            return RouteChange.NEWLY_SELECTED;
        }
        return before.isPresent() ? RouteChange.NO_LONGER_SELECTED : RouteChange.NOT_SELECTED_EITHER;
    }

    private static Optional<RouteKey> selected(RoutingDecision decision) {
        return decision instanceof RoutingDecision.Selected selected ? Optional.of(selected.route()) : Optional.empty();
    }

    private static List<RouteKey> rankedKeys(RoutingDecision decision) {
        List<RankedCandidate> candidates = switch (decision) {
            case RoutingDecision.Selected selected -> selected.candidates();
            case RoutingDecision.Abstain abstain -> abstain.candidates();
            case RoutingDecision.NoEligibleRoute ignored -> List.of();
        };
        return candidates.stream().map(RankedCandidate::key).toList();
    }

    private static Optional<CohortBinding> cohort(RoutingDecision decision) {
        return switch (decision) {
            case RoutingDecision.Selected selected -> Optional.of(selected.cohortBinding());
            case RoutingDecision.Abstain abstain -> abstain.cohortBinding();
            case RoutingDecision.NoEligibleRoute ignored -> Optional.empty();
        };
    }
}
