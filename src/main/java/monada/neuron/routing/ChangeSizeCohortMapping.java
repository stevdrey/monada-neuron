package monada.neuron.routing;

import monada.neuron.routing.features.Feature;
import monada.neuron.routing.features.TaskFeatures;

import java.util.Objects;

/**
 * Reference mapping that buckets by change size: {@code S} up to {@code smallMax}, {@code M} up to
 * {@code mediumMax}, {@code L} above, {@code UNKNOWN} when the size is unknown.
 *
 * <p>The thresholds are host units and an unvalidated policy choice, not evidence that change size separates
 * routes. The default is illustrative.
 *
 * @param bucketMappingVersion version token of this assignment (change it with the thresholds)
 * @param smallMax inclusive upper bound of {@code S}, non-negative
 * @param mediumMax inclusive upper bound of {@code M}, at least {@code smallMax}
 */
public record ChangeSizeCohortMapping(String bucketMappingVersion, long smallMax, long mediumMax)
        implements CohortMapping {

    /** Illustrative default: S up to 50, M up to 500. */
    public static final ChangeSizeCohortMapping DEFAULT = new ChangeSizeCohortMapping("change-size/1", 50, 500);

    /** Validates the version and thresholds. */
    public ChangeSizeCohortMapping {
        RoutingTokens.require(bucketMappingVersion, "bucketMappingVersion");
        if (smallMax < 0 || mediumMax < smallMax) {
            throw new IllegalArgumentException(
                    "thresholds must satisfy 0 <= smallMax <= mediumMax, got: " + smallMax + ", " + mediumMax);
        }
    }

    @Override
    public String featureSchemaVersion() {
        return TaskFeatures.SCHEMA_VERSION;
    }

    @Override
    public String bucketOf(TaskFeatures features) {
        Objects.requireNonNull(features, "features must not be null");
        if (!(features.changeSize() instanceof Feature.Known<Long> known)) {
            return UNKNOWN_BUCKET;
        }
        long size = known.value();
        return size <= smallMax ? "S" : size <= mediumMax ? "M" : "L";
    }
}
