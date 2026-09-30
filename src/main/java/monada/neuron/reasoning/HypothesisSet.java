package monada.neuron.reasoning;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded snapshot of the hypotheses produced by one reasoning stage.
 *
 * <p>Hypotheses are ephemeral cycle-local artifacts, not long-term memory. Sequences are
 * contiguous from zero, so {@link #get(int)} is an O(1) index lookup.
 *
 * @param hypotheses hypotheses ordered by sequence
 * @param limits capacities the snapshot satisfies
 */
public record HypothesisSet(List<Hypothesis> hypotheses, HypothesisLimits limits) {

    /** Shared empty snapshot under default limits. */
    public static final HypothesisSet EMPTY = new HypothesisSet(List.of(), HypothesisLimits.DEFAULT);

    /** Validates ordering, uniqueness, and capacity. */
    public HypothesisSet {
        Objects.requireNonNull(limits, "limits must not be null");
        hypotheses = List.copyOf(Objects.requireNonNull(hypotheses, "hypotheses must not be null"));
        if (hypotheses.size() > limits.maxCandidates()) {
            throw new IllegalArgumentException(
                    "hypotheses exceed maxCandidates: " + hypotheses.size());
        }
        var propositions = new HashSet<Proposition>(hypotheses.size() * 2);
        for (var i = 0; i < hypotheses.size(); i++) {
            var hypothesis = hypotheses.get(i);
            if (hypothesis.sequence() != i) {
                throw new IllegalArgumentException(
                        "hypothesis sequences must be contiguous from zero, got "
                                + hypothesis.sequence() + " at index " + i);
            }
            if (hypothesis.evidence().size() > limits.maxEvidencePerCandidate()) {
                throw new IllegalArgumentException(
                        "evidence exceeds maxEvidencePerCandidate for hypothesis " + i);
            }
            if (!propositions.add(hypothesis.proposition())) {
                throw new IllegalArgumentException(
                        "duplicate proposition for hypothesis " + i);
            }
        }
    }

    /** Returns the hypothesis with the given cycle-local sequence. */
    public Hypothesis get(int sequence) {
        return hypotheses.get(sequence);
    }

    /** Returns the number of retained hypotheses. */
    public int size() {
        return hypotheses.size();
    }

    /** Returns whether no hypothesis is retained. */
    public boolean isEmpty() {
        return hypotheses.isEmpty();
    }
}
