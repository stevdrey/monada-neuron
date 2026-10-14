package monada.neuron.routing.feedback;

import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.Objects;

/**
 * The cohort a routing preference Node is bound to (contract section 7):
 * {@code (scopeId, stageKind, cohortBucket, RouteId, RouteVersion)}.
 *
 * <p>The natural order compares the parts in declaration order, tokens by code point, so a sorted array of keys is a
 * deterministic index.
 *
 * @param scopeId scope token
 * @param stageKind stage kind token of the request
 * @param cohortBucket bucket recorded in the decision's cohort binding
 * @param route executed route
 */
public record CohortKey(String scopeId, String stageKind, String cohortBucket, RouteKey route)
        implements Comparable<CohortKey> {

    /** Validates the parts. */
    public CohortKey {
        OpaqueTokens.require(scopeId, "scopeId");
        OpaqueTokens.require(stageKind, "stageKind");
        OpaqueTokens.require(cohortBucket, "cohortBucket");
        Objects.requireNonNull(route, "route must not be null");
    }

    @Override
    public int compareTo(CohortKey other) {
        int byScope = OpaqueTokens.compareCodePoints(scopeId, other.scopeId);
        if (byScope != 0) {
            return byScope;
        }
        int byStage = OpaqueTokens.compareCodePoints(stageKind, other.stageKind);
        if (byStage != 0) {
            return byStage;
        }
        int byBucket = OpaqueTokens.compareCodePoints(cohortBucket, other.cohortBucket);
        return byBucket != 0 ? byBucket : route.compareTo(other.route);
    }
}
