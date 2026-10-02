package monada.neuron.reasoning;

import java.util.List;
import java.util.Objects;

/**
 * Immutable cycle-local candidate explanation with ordered evidence.
 *
 * <p>Identity within a cycle is {@code sequence}, the index assigned by {@link HypothesisSet}.
 * Equality is structural over all components; the sequence is meaningless across cycles.
 *
 * @param sequence cycle-local index of this hypothesis
 * @param proposition statement asserted
 * @param evidence evidence in insertion order
 */
public record Hypothesis(int sequence, Proposition proposition, List<Evidence> evidence) {

    /** Validates and snapshots components. */
    public Hypothesis {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative, got: " + sequence);
        }
        Objects.requireNonNull(proposition, "proposition must not be null");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
    }
}
