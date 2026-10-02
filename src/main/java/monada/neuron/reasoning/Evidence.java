package monada.neuron.reasoning;

import java.util.Objects;

/**
 * Bounded provenance record attached to a hypothesis.
 *
 * <p>Evidence holds only compact ids and a weight. It never retains signals, propagation graphs,
 * memory-provider objects, or action payloads.
 */
public sealed interface Evidence
        permits SignalEvidence, MemoryReferenceEvidence {

    /** Returns whether this evidence supports, contradicts, or is neutral to the hypothesis. */
    EvidenceRelation relation();

    /** Returns the finite evidence weight in the range {@code (0, 1]}. */
    double weight();

    /** Validates the relation and weight shared by all evidence variants. */
    static void validate(EvidenceRelation relation, double weight) {
        Objects.requireNonNull(relation, "relation must not be null");
        if (!Double.isFinite(weight) || weight <= 0.0 || weight > 1.0) {
            throw new IllegalArgumentException("weight must be finite in (0, 1], got: " + weight);
        }
    }
}
