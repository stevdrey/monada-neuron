package monada.neuron.reasoning;

/**
 * Opaque memory reference id; carries no Resonance Store or provider type.
 *
 * @param reference non-negative reference id
 * @param relation how the referenced item bears on the hypothesis
 * @param weight finite weight in {@code (0, 1]}
 */
public record MemoryReferenceEvidence(long reference, EvidenceRelation relation, double weight) implements Evidence {

    /** Validates the reference and weight. */
    public MemoryReferenceEvidence {
        Evidence.validate(reference, "reference", relation, weight);
    }
}
