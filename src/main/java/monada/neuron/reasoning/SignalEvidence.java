package monada.neuron.reasoning;

/**
 * Cycle-local signal occurrence sequence assigned by the cognitive context.
 *
 * @param signalSequence non-negative reference id
 * @param relation how the referenced item bears on the hypothesis
 * @param weight finite weight in {@code (0, 1]}
 */
public record SignalEvidence(long signalSequence, EvidenceRelation relation, double weight) implements Evidence {

    /** Validates the reference and weight. */
    public SignalEvidence {
        Evidence.validate(signalSequence, "signalSequence", relation, weight);
    }
}
