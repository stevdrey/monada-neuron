package monada.neuron.routing;

import monada.neuron.routing.catalog.RouteKey;

import java.util.Comparator;
import java.util.Objects;

/**
 * Learned preference of one route inside one cohort: an ordinal, not a calibrated success probability.
 *
 * @param stageKind stage kind token
 * @param cohortBucket bucket token
 * @param route route identity; a different {@code RouteVersion} is a different cohort
 * @param preferenceValue finite effective preference (amplitude minus baseline); higher is preferred
 * @param supportingObservations applied observations behind the value, non-negative
 */
public record CohortPreference(
        String stageKind, String cohortBucket, RouteKey route, double preferenceValue, int supportingObservations) {

    /** Canonical order: stage kind and bucket by code point, then route. */
    static final Comparator<CohortPreference> ORDER = Comparator
            .comparing(CohortPreference::stageKind, RoutingTokens.CODE_POINT_ORDER)
            .thenComparing(CohortPreference::cohortBucket, RoutingTokens.CODE_POINT_ORDER)
            .thenComparing(CohortPreference::route);

    /** Validates parts; {@code -0.0} is normalized to {@code 0.0}. */
    public CohortPreference {
        RoutingTokens.require(stageKind, "stageKind");
        RoutingTokens.require(cohortBucket, "cohortBucket");
        Objects.requireNonNull(route, "route must not be null");
        if (!Double.isFinite(preferenceValue)) {
            throw new IllegalArgumentException("preferenceValue must be finite, got: " + preferenceValue);
        }
        if (supportingObservations < 0) {
            throw new IllegalArgumentException(
                    "supportingObservations must be non-negative, got: " + supportingObservations);
        }
        preferenceValue = preferenceValue + 0.0;
    }
}
