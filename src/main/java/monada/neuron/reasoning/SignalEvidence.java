package monada.neuron.reasoning;

/**
 * Cycle-local signal occurrence sequence assigned by the cognitive context.
 *
 * <p>The sequence is validated against the active context by the cycle, so it cannot point to an
 * occurrence that was never accepted.
 *
 * @param signalSequence non-negative occurrence sequence
 * @param relation how the referenced signal bears on the hypothesis
 * @param weight finite weight in {@code (0, 1]}
 */
public record SignalEvidence(long signalSequence, EvidenceRelation relation, double weight)
        implements Evidence {

    /** Validates the sequence and weight. */
    public SignalEvidence {
        if (signalSequence < 0) {
            throw new IllegalArgumentException(
                    "signalSequence must be non-negative, got: " + signalSequence);
        }
        Evidence.validate(relation, weight);
    }
}
