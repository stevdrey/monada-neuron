package monada.neuron.routing;

import monada.neuron.routing.features.TaskFeatures;

/**
 * Pure, versioned function from {@link TaskFeatures} to a bounded cohort bucket token.
 *
 * <p>Implementations must be deterministic and side-effect free. An unknown feature value maps to an explicit
 * {@code UNKNOWN} bucket, never to a measured zero. Any change to the assignment needs a new
 * {@link #bucketMappingVersion()}.
 */
public interface CohortMapping {

    /** Bucket used when the relevant feature is unknown. */
    String UNKNOWN_BUCKET = "UNKNOWN";

    /** Version of this feature-to-bucket assignment. */
    String bucketMappingVersion();

    /** Feature schema this mapping was built for. */
    String featureSchemaVersion();

    /** Returns the bucket token (1 to 128 code points) for the features. */
    String bucketOf(TaskFeatures features);
}
