package monada.neuron.routing.features;

/**
 * What a feature readiness diagnostic observed about one {@link FeatureDimension} of a {@link TaskFeatures}.
 *
 * <p>Diagnostics are information only: they are never eligibility, readiness, authentication or authorization,
 * and no routing decision reads them.
 */
public enum FieldStatus {
    /** The host supplied a value, and for {@link FeatureDimension#STAGE_KIND} it equals the expected token. */
    KNOWN,
    /** The host marked the value explicitly unknown; it is never read as a zero, an empty set or a default. */
    UNKNOWN,
    /** The known {@link FeatureDimension#STAGE_KIND} differs from the expected token; no other dimension has it. */
    MISMATCH
}
