package monada.neuron.routing.review;

import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The excluded routes of one decision that share a primary reason, with every additional reason intact.
 *
 * <p>A review is information only: it is never eligibility, readiness, authentication or authorization, and the
 * selected route stays advice that the host must recheck before execution.
 *
 * @param primaryReason the reason every member names first
 * @param exclusions 1 to {@value RouteCatalog#MAX_ROUTES} of the decision's own exclusion records, unique per route,
 *         stored in canonical route order
 */
public record ExclusionGroup(EligibilityReason primaryReason, List<EligibilityReport.Exclusion> exclusions) {

    /** Validates membership and copies in canonical route order. */
    public ExclusionGroup {
        Objects.requireNonNull(primaryReason, "primaryReason must not be null");
        Objects.requireNonNull(exclusions, "exclusions must not be null");
        if (exclusions.isEmpty() || exclusions.size() > RouteCatalog.MAX_ROUTES) {
            throw new IllegalArgumentException("a group holds 1 to " + RouteCatalog.MAX_ROUTES
                    + " exclusions, got: " + exclusions.size());
        }
        var sorted = new ArrayList<EligibilityReport.Exclusion>(exclusions.size());
        for (EligibilityReport.Exclusion exclusion : exclusions) {
            Objects.requireNonNull(exclusion, "exclusion must not be null");
            if (exclusion.primaryReason() != primaryReason) {
                throw new IllegalArgumentException("exclusion " + exclusion.key() + " names " + exclusion.primaryReason()
                        + " first, not " + primaryReason);
            }
            sorted.add(exclusion);
        }
        sorted.sort(Comparator.comparing(EligibilityReport.Exclusion::key));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).key().equals(sorted.get(i).key())) {
                throw new IllegalArgumentException("duplicate exclusion for " + sorted.get(i).key());
            }
        }
        exclusions = List.copyOf(sorted);
    }
}
