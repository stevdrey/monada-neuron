package monada.neuron.routing.features;

import monada.neuron.routing.token.OpaqueTokens;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Outcome of {@link TaskFeatureReadiness#assess}: either a per dimension report over a schema v1 snapshot, or a
 * reported schema mismatch with no rows. A newer schema is never read or guessed.
 *
 * <p>Diagnostics are information only: they are never eligibility, readiness, authentication or authorization,
 * and no routing decision reads them.
 */
public sealed interface FeatureReadinessAssessment
        permits FeatureReadinessAssessment.Assessed, FeatureReadinessAssessment.SchemaMismatch {

    /**
     * A report over a {@link TaskFeatures#SCHEMA_VERSION} snapshot. It holds only its three inputs; the eight rows
     * are derived from them on every call and never stored, so no value can claim more than its inputs support.
     *
     * <p>A report with nothing missing and nothing inconsistent is not a readiness outcome and is not permission.
     *
     * <p>Diagnostics are information only: they are never eligibility, readiness, authentication or authorization,
     * and no routing decision reads them.
     *
     * @param features the snapshot as given, the same instance
     * @param expectedStageKind the stage kind token of the request the host is about to build
     * @param required the dimensions the caller requires for its own preview, unmodifiable, in declaration order
     */
    record Assessed(TaskFeatures features, String expectedStageKind, Set<FeatureDimension> required)
            implements FeatureReadinessAssessment {

        /**
         * Checks the arguments in a fixed order (nulls, the token, null elements, then the schema), and copies
         * {@code required} into an unmodifiable {@link EnumSet}.
         */
        public Assessed {
            Objects.requireNonNull(features, "features must not be null");
            Objects.requireNonNull(expectedStageKind, "expectedStageKind must not be null");
            Objects.requireNonNull(required, "required must not be null");
            OpaqueTokens.require(expectedStageKind, "expectedStageKind");
            EnumSet<FeatureDimension> copy = EnumSet.noneOf(FeatureDimension.class);
            for (FeatureDimension dimension : required) {
                copy.add(Objects.requireNonNull(dimension, "required must not contain null"));
            }
            if (!TaskFeatures.SCHEMA_VERSION.equals(features.schemaVersion())) {
                throw new IllegalArgumentException("features must use schema " + TaskFeatures.SCHEMA_VERSION
                        + ", got: " + features.schemaVersion());
            }
            required = Collections.unmodifiableSet(copy);
        }

        /**
         * Derives the rows from the components.
         *
         * @return exactly one row per {@link FeatureDimension}, in declaration order, unmodifiable
         */
        public List<FieldDiagnostic> diagnostics() {
            FeatureDimension[] dimensions = FeatureDimension.values();
            FieldDiagnostic[] rows = new FieldDiagnostic[dimensions.length];
            for (FeatureDimension dimension : dimensions) {
                rows[dimension.ordinal()] = FieldDiagnostic.of(dimension, status(dimension),
                        required.contains(dimension));
            }
            return List.of(rows);
        }

        /**
         * Lists the unknown dimensions the caller required.
         *
         * @return the dimensions whose severity is {@code MISSING_FOR_HOST_PREVIEW}, in declaration order, unmodifiable
         */
        public List<FeatureDimension> missingForHostPreview() {
            return dimensionsWith(DiagnosticSeverity.MISSING_FOR_HOST_PREVIEW);
        }

        /**
         * Lists the dimensions that contradict the request the host is about to build.
         *
         * @return the dimensions whose severity is {@code INCONSISTENT_WITH_REQUEST}, in declaration order,
         *     unmodifiable
         */
        public List<FeatureDimension> inconsistentWithRequest() {
            return dimensionsWith(DiagnosticSeverity.INCONSISTENT_WITH_REQUEST);
        }

        private List<FeatureDimension> dimensionsWith(DiagnosticSeverity severity) {
            List<FeatureDimension> matches = new ArrayList<>();
            for (FieldDiagnostic row : diagnostics()) {
                if (row.severity() == severity) {
                    matches.add(row.dimension());
                }
            }
            return List.copyOf(matches);
        }

        private FieldStatus status(FeatureDimension dimension) {
            return switch (dimension) {
                case STAGE_KIND -> stageKindStatus();
                case CATEGORY -> presence(features.category());
                case LANGUAGES -> presence(features.languages());
                case DOMAINS -> presence(features.domains());
                case CHANGE_SIZE -> presence(features.changeSize());
                case CONTEXT_SIZE -> presence(features.contextSize());
                case TESTS -> presence(features.tests());
                case SECURITY -> presence(features.security());
            };
        }

        private FieldStatus stageKindStatus() {
            if (features.stageKind() instanceof Feature.Known<String>(var value)) {
                return value.equals(expectedStageKind) ? FieldStatus.KNOWN : FieldStatus.MISMATCH;
            }
            return FieldStatus.UNKNOWN;
        }

        private FieldStatus presence(Feature<?> feature) {
            return feature instanceof Feature.Known<?> ? FieldStatus.KNOWN : FieldStatus.UNKNOWN;
        }
    }

    /**
     * The features declared a schema version this assessment does not implement, so no field was read.
     *
     * <p>A mismatch carries no rows and so no stage kind diagnostic.
     *
     * <p>Diagnostics are information only: they are never eligibility, readiness, authentication or authorization,
     * and no routing decision reads them.
     *
     * @param expected schema version the assessment supports
     * @param actual schema version declared by the features
     */
    record SchemaMismatch(String expected, String actual) implements FeatureReadinessAssessment {

        /** Requires two valid, different version tokens. */
        public SchemaMismatch {
            OpaqueTokens.require(expected, "expected");
            OpaqueTokens.require(actual, "actual");
            if (expected.equals(actual)) {
                throw new IllegalArgumentException("expected and actual must differ, both were: " + expected);
            }
        }
    }
}
