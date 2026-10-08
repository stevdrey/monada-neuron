package monada.neuron.routing.features;

import monada.neuron.signal.Signal;

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

        /** Requires non-null parts and copies the lists. */
        public Encoded {
            Objects.requireNonNull(features, "features must not be null");
            signals = List.copyOf(signals);
            Objects.requireNonNull(policyId, "policyId must not be null");
            saturatedDimensions = List.copyOf(saturatedDimensions);
            otherBucketDimensions = List.copyOf(otherBucketDimensions);
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
