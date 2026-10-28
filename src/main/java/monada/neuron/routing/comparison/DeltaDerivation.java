package monada.neuron.routing.comparison;

import monada.neuron.routing.CohortBinding;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.RouteKey;

import java.util.List;
import java.util.Optional;

/**
 * Derives a {@link ScenarioDelta} from two decisions: every field but the eligibility changes comes from the decisions
 * alone, so {@link ScenarioComparison.Compared} can check a delta against the decisions it describes. It holds no state.
 */
final class DeltaDerivation {

    /** The delta between two decisions, with eligibility changes the caller derived from the same scenarios. */
    ScenarioDelta between(RoutingDecision before, RoutingDecision after, List<EligibilityChange> eligibilityChanges) {
        return new ScenarioDelta(
                routeChange(selected(before), selected(after)),
                !before.equals(after),
                !rankedKeys(before).equals(rankedKeys(after)) || truncated(before) != truncated(after),
                !cohort(before).equals(cohort(after)),
                eligibilityChanges);
    }

    private RouteChange routeChange(Optional<RouteKey> before, Optional<RouteKey> after) {
        if (before.isPresent() && after.isPresent()) {
            return before.equals(after) ? RouteChange.SAME_ROUTE : RouteChange.DIFFERENT_ROUTE;
        }
        if (after.isPresent()) {
            return RouteChange.NEWLY_SELECTED;
        }
        return before.isPresent() ? RouteChange.NO_LONGER_SELECTED : RouteChange.NOT_SELECTED_EITHER;
    }

    private Optional<RouteKey> selected(RoutingDecision decision) {
        return decision instanceof RoutingDecision.Selected selected ? Optional.of(selected.route()) : Optional.empty();
    }

    private List<RouteKey> rankedKeys(RoutingDecision decision) {
        List<RankedCandidate> candidates = switch (decision) {
            case RoutingDecision.Selected selected -> selected.candidates();
            case RoutingDecision.Abstain abstain -> abstain.candidates();
            case RoutingDecision.NoEligibleRoute ignored -> List.of();
        };
        return candidates.stream().map(RankedCandidate::key).toList();
    }

    /** Whether the decision ranked more eligible routes than it lists. */
    private boolean truncated(RoutingDecision decision) {
        return switch (decision) {
            case RoutingDecision.Selected selected -> selected.candidatesTruncated();
            case RoutingDecision.Abstain abstain -> abstain.candidatesTruncated();
            case RoutingDecision.NoEligibleRoute ignored -> false;
        };
    }

    private Optional<CohortBinding> cohort(RoutingDecision decision) {
        return switch (decision) {
            case RoutingDecision.Selected selected -> Optional.of(selected.cohortBinding());
            case RoutingDecision.Abstain abstain -> abstain.cohortBinding();
            case RoutingDecision.NoEligibleRoute ignored -> Optional.empty();
        };
    }
}
