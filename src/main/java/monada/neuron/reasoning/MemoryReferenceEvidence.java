package monada.neuron.reasoning;

import java.util.Objects;

/**
 * Opaque memory reference, identical to {@code ResonanceMemoryResult.reference()}, carrying no
 * Resonance Store or provider type.
 *
 * @param reference non-blank reference of at most {@link #MAX_REFERENCE_LENGTH} characters
 * @param relation how the referenced memory bears on the hypothesis
 * @param weight finite weight in {@code (0, 1]}
 */
public record MemoryReferenceEvidence(String reference, EvidenceRelation relation, double weight)
        implements Evidence {

    /** Upper bound that prevents evidence from retaining large external payloads. */
    public static final int MAX_REFERENCE_LENGTH = 256;

    /** Validates the reference and weight. */
    public MemoryReferenceEvidence {
        Objects.requireNonNull(reference, "reference must not be null");
        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
        if (reference.length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(
                    "reference must be at most " + MAX_REFERENCE_LENGTH
                            + " characters, got: " + reference.length());
        }
        Evidence.validate(relation, weight);
    }
}
