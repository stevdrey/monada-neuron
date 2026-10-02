package monada.neuron.reasoning;

import java.util.Objects;

/**
 * Opaque memory reference, identical to {@code ResonanceMemoryResult.reference()}, carrying no
 * Resonance Store or provider type.
 *
 * <p>The accepted domain equals the memory port's: any non-blank string. Bounding reference size
 * is the responsibility of the memory adapter.
 *
 * @param reference non-blank opaque reference
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
        Evidence.validate(relation, weight);
    }
}
