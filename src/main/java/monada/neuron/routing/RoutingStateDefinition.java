package monada.neuron.routing;

import java.util.Objects;

/**
 * The state-transition definition a {@link RoutingPreference} is built under, identified by one composite
 * {@code mappingVersion}.
 *
 * <p>In #62 it groups the {@link CohortMapping}; the initial Node state, adaptation configuration and feedback
 * scoring rules join the same {@code mappingVersion} in #65 and #66. The version must change whenever any part
 * changes; a snapshot built under another version is incompatible and never silently reused.
 *
 * @param mappingVersion composite version token
 * @param cohortMapping feature-to-bucket assignment
 */
public record RoutingStateDefinition(String mappingVersion, CohortMapping cohortMapping) {

    /** Validates parts. */
    public RoutingStateDefinition {
        RoutingTokens.require(mappingVersion, "mappingVersion");
        Objects.requireNonNull(cohortMapping, "cohortMapping must not be null");
    }

    /** Reference definition {@code routing-state/1} over {@link ChangeSizeCohortMapping#DEFAULT}. */
    public static RoutingStateDefinition reference() {
        return new RoutingStateDefinition("routing-state/1", ChangeSizeCohortMapping.DEFAULT);
    }
}
