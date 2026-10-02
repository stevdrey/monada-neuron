package monada.neuron.reasoning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Sequential, cycle-local accumulator for a {@link HypothesisSet}.
 *
 * <p>Not thread-safe. Capacity exhaustion is an expected outcome and is reported without throwing,
 * mirroring the {@code tryRecord*} style of the cognitive context.
 */
public final class HypothesisSetBuilder {

    private final HypothesisLimits limits;
    private final List<Proposition> propositions;
    private final List<List<Evidence>> evidence;
    private final HashMap<Proposition, Integer> sequenceByProposition;

    /** Creates an empty builder bound to the given limits. */
    public HypothesisSetBuilder(HypothesisLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        var initial = Math.min(limits.maxCandidates(), 16);
        this.propositions = new ArrayList<>(initial);
        this.evidence = new ArrayList<>(initial);
        this.sequenceByProposition = new HashMap<>(initial * 2);
    }

    /**
     * Registers a proposition and returns its sequence.
     *
     * <p>An equivalent proposition returns the existing sequence, so evidence merges
     * deterministically. Returns empty when a new candidate would exceed capacity.
     */
    public OptionalInt propose(Proposition proposition) {
        Objects.requireNonNull(proposition, "proposition must not be null");
        var existing = sequenceByProposition.get(proposition);
        if (existing != null) {
            return OptionalInt.of(existing);
        }
        if (propositions.size() >= limits.maxCandidates()) {
            return OptionalInt.empty();
        }
        var sequence = propositions.size();
        propositions.add(proposition);
        evidence.add(new ArrayList<>(2));
        sequenceByProposition.put(proposition, sequence);
        return OptionalInt.of(sequence);
    }

    /** Appends evidence in order; returns false when the candidate's evidence capacity is full. */
    public boolean addEvidence(int sequence, Evidence item) {
        Objects.requireNonNull(item, "evidence must not be null");
        if (sequence < 0 || sequence >= propositions.size()) {
            throw new IllegalArgumentException("unknown hypothesis sequence: " + sequence);
        }
        var bucket = evidence.get(sequence);
        if (bucket.size() >= limits.maxEvidencePerCandidate()) {
            return false;
        }
        bucket.add(item);
        return true;
    }

    /** Returns an immutable snapshot; later builder mutation does not affect it. */
    public HypothesisSet build() {
        var hypotheses = new ArrayList<Hypothesis>(propositions.size());
        for (var i = 0; i < propositions.size(); i++) {
            hypotheses.add(new Hypothesis(i, propositions.get(i), evidence.get(i)));
        }
        return new HypothesisSet(hypotheses, limits);
    }
}
