package monada.neuron.routing;

/**
 * Immutable binding of a decision to the cohort the policy computed from the request's features. A later rebuild
 * assigns a cohort only from this binding; features are never recomputed.
 *
 * @param cohortBucket bucket token
 * @param bucketMappingVersion version of the {@link CohortMapping}
 * @param mappingVersion version of the complete {@link RoutingStateDefinition}
 * @param featureSchemaVersion feature schema of the request
 */
public record CohortBinding(
        String cohortBucket, String bucketMappingVersion, String mappingVersion, String featureSchemaVersion) {

    /** Validates tokens. */
    public CohortBinding {
        RoutingTokens.require(cohortBucket, "cohortBucket");
        RoutingTokens.require(bucketMappingVersion, "bucketMappingVersion");
        RoutingTokens.require(mappingVersion, "mappingVersion");
        RoutingTokens.require(featureSchemaVersion, "featureSchemaVersion");
    }
}
