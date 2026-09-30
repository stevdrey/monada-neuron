package monada.neuron.reasoning;

/**
 * Opaque action outcome reference id; carries no tool-provider type.
 *
 * @param reference non-negative reference id
 * @param relation how the referenced item bears on the hypothesis
 * @param weight finite weight in {@code (0, 1]}
 */
public record ActionReferenceEvidence(long reference, EvidenceRelation relation, double weight) implements Evidence {

    /** Validates the reference and weight. */
    public ActionReferenceEvidence {
        Evidence.validate(reference, "reference", relation, weight);
    }
}
