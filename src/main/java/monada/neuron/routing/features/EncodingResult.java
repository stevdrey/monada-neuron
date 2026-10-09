package monada.neuron.routing.features;

import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of encoding one {@link TaskFeatures}: either encoded signals next to the typed features, or an
 * expected, reported schema mismatch. Unsupported input is never guessed.
 */
public sealed interface EncodingResult permits EncodingResult.Encoded, EncodingResult.SchemaMismatch {

    /**
     * Encoded observation signals together with the authoritative typed features.
     *
     * <p>Signals are a lossy numeric view. Saturated numeric fields and {@code OTHER}-bucket categorical
     * fields are listed so a collision or clamp is never invisible; exact eligibility must use {@link #features}.
     *
     * @param features the typed snapshot the signals were derived from
     * @param signals immutable {@code OBSERVATION} signals in canonical dimension then tag order
     * @param policyId id of the encoding policy used
     * @param policyVersion version of the encoding policy used
     * @param saturatedDimensions numeric dimensions clamped to the policy ceiling, in dimension order
     * @param otherBucketDimensions categorical dimensions where at least one token was outside the vocabulary
     */
    record Encoded(
            TaskFeatures features,
            List<Signal> signals,
            String policyId,
            long policyVersion,
            List<FeatureDimension> saturatedDimensions,
            List<FeatureDimension> otherBucketDimensions) implements EncodingResult {

        /**
         * Checks the structural invariants the encoder guarantees and copies the lists: at most
         * {@link TaskFeatureEncoder#MAX_SIGNALS} non-null {@code OBSERVATION} signals, a valid policy token, a
         * positive version, and dimension lists that are strictly ordered without duplicates, with saturation
         * only on numeric and other-bucket only on categorical dimensions. Consistency of the signals with
         * {@code features} is guaranteed only for values produced by {@link TaskFeatureEncoder}.
         */
        public Encoded {
            Objects.requireNonNull(features, "features must not be null");
            signals = List.copyOf(signals);
            if (signals.size() > TaskFeatureEncoder.MAX_SIGNALS) {
                throw new IllegalArgumentException("signals exceed " + TaskFeatureEncoder.MAX_SIGNALS
                        + ", got: " + signals.size());
            }
            for (Signal signal : signals) {
                if (signal.kind() != SignalKind.OBSERVATION) {
                    throw new IllegalArgumentException("signals must be OBSERVATION, got: " + signal.kind());
                }
            }
            FeatureTokens.require(policyId, "policyId");
            if (policyVersion < 1) {
                throw new IllegalArgumentException("policyVersion must be positive, got: " + policyVersion);
            }
            saturatedDimensions = orderedSubset(saturatedDimensions, "saturatedDimensions",
                    FeatureDimension.CHANGE_SIZE, FeatureDimension.CONTEXT_SIZE);
            otherBucketDimensions = orderedSubset(otherBucketDimensions, "otherBucketDimensions",
                    FeatureDimension.STAGE_KIND, FeatureDimension.CATEGORY, FeatureDimension.LANGUAGES,
                    FeatureDimension.DOMAINS);
        }

        private static List<FeatureDimension> orderedSubset(List<FeatureDimension> dimensions, String name,
                FeatureDimension... allowed) {
            List<FeatureDimension> copy = List.copyOf(dimensions);
            List<FeatureDimension> permitted = List.of(allowed);
            FeatureDimension previous = null;
            for (FeatureDimension dimension : copy) {
                if (!permitted.contains(dimension)) {
                    throw new IllegalArgumentException(name + " does not allow " + dimension);
                }
                if (previous != null && dimension.ordinal() <= previous.ordinal()) {
                    throw new IllegalArgumentException(name + " must be strictly ordered without duplicates");
                }
                previous = dimension;
            }
            return copy;
        }
    }

    /**
     * The features declared a schema version the encoder does not implement.
     *
     * @param expected schema version the encoder supports
     * @param actual schema version declared by the features
     */
    record SchemaMismatch(String expected, String actual) implements EncodingResult {

        /** Requires both versions. */
        public SchemaMismatch {
            Objects.requireNonNull(expected, "expected must not be null");
            Objects.requireNonNull(actual, "actual must not be null");
        }
    }
}
