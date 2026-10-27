package monada.neuron.routing.features;

import monada.neuron.routing.token.OpaqueTokens;

import java.util.Objects;
import java.util.Set;

/**
 * Reports which {@link TaskFeatures} fields a host supplied, which are unknown, and whether the stage kind agrees
 * with the request the host is about to build, so a preview can explain what is known before any advice is shown.
 *
 * <p>The assessment is pure: it reads only its three arguments (no task text, file, clock or environment), keeps
 * no state, and does constant work over the eight dimensions. Nothing in routing consults it.
 *
 * <p>Diagnostics are information only: they are never eligibility, readiness, authentication or authorization,
 * and no routing decision reads them.
 */
public final class TaskFeatureReadiness {

    /** Creates the assessor. */
    public TaskFeatureReadiness() {
    }

    /**
     * Assesses the features against the expected stage kind and the caller's required dimensions, or reports a
     * schema mismatch without throwing. Argument errors are checked first and throw.
     *
     * @param features validated typed features
     * @param expectedStageKind stage kind token of the request the host is about to build
     * @param required dimensions the caller requires for its preview; may be empty, must not contain null
     * @return {@link FeatureReadinessAssessment.Assessed} or {@link FeatureReadinessAssessment.SchemaMismatch}
     */
    public FeatureReadinessAssessment assess(TaskFeatures features, String expectedStageKind,
            Set<FeatureDimension> required) {
        Objects.requireNonNull(features, "features must not be null");
        Objects.requireNonNull(expectedStageKind, "expectedStageKind must not be null");
        Objects.requireNonNull(required, "required must not be null");
        OpaqueTokens.require(expectedStageKind, "expectedStageKind");
        // a loop, not required.contains(null), which throws on Set.of
        for (FeatureDimension dimension : required) {
            Objects.requireNonNull(dimension, "required must not contain null");
        }
        if (!TaskFeatures.SCHEMA_VERSION.equals(features.schemaVersion())) {
            return new FeatureReadinessAssessment.SchemaMismatch(TaskFeatures.SCHEMA_VERSION,
                    features.schemaVersion());
        }
        return new FeatureReadinessAssessment.Assessed(features, expectedStageKind, required);
    }
}
