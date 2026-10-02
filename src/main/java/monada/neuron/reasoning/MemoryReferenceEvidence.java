package monada.neuron.reasoning;

import monada.neuron.memory.ResonanceMemoryResult;

import java.util.Objects;

/**
 * Opaque memory reference, identical to {@code ResonanceMemoryResult.reference()}, carrying no
 * Resonance Store or provider type.
 *
 * <p>The accepted domain equals the memory port's: non-blank and at most
 * {@link ResonanceMemoryResult#MAX_REFERENCE_LENGTH} characters, defined once and shared.
 *
 * @param reference non-blank opaque reference within the shared maximum length
 * @param relation how the referenced memory bears on the hypothesis
 * @param weight finite weight in {@code (0, 1]}
 */
public record MemoryReferenceEvidence(String reference, EvidenceRelation relation, double weight)
        implements Evidence {

    /** Validates the reference and weight. */
    public MemoryReferenceEvidence {
        Objects.requireNonNull(reference, "reference must not be null");
        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
        if (reference.length() > ResonanceMemoryResult.MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(
                    "reference must be at most " + ResonanceMemoryResult.MAX_REFERENCE_LENGTH
                            + " characters, got: " + reference.length());
        }
        Evidence.validate(relation, weight);
    }
}
