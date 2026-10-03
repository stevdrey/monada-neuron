package monada.neuron.evaluation;

import monada.neuron.reasoning.HypothesisSet;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of evaluating a candidate set: the scored set and the selected candidates in
 * rank order.
 *
 * <p>The result carries the {@link HypothesisSet} it scored, so the selected sequences always resolve
 * against the set they were computed from and a mismatched pairing cannot be constructed. Rank order
 * is defined by {@code HypothesisRanking} (score descending, then lower sequence first) and each
 * candidate appears at most once. Only selected candidates are retained beyond the shared set.
 *
 * @param evaluated the candidate set that was scored; selected sequences index into it
 * @param selected ranked selected candidates
 * @param requestedMaxSelected the selection bound requested by the caller
 */
public record HypothesisEvaluation(
        HypothesisSet evaluated,
        List<EvaluatedHypothesis> selected,
        int requestedMaxSelected) {

    private static final int PAIRWISE_LIMIT = 16;

    /** Validates counts, sequence range, uniqueness, and the documented rank order. */
    public HypothesisEvaluation {
        Objects.requireNonNull(evaluated, "evaluated must not be null");
        selected = List.copyOf(Objects.requireNonNull(selected, "selected must not be null"));
        if (requestedMaxSelected < 0) {
            throw new IllegalArgumentException(
                    "requestedMaxSelected must not be negative, got: " + requestedMaxSelected);
        }
        var evaluatedCount = evaluated.size();
        if (selected.size() > Math.min(evaluatedCount, requestedMaxSelected)) {
            throw new IllegalArgumentException(
                    "selected size " + selected.size() + " exceeds min(evaluatedCount, requestedMaxSelected)");
        }
        EvaluatedHypothesis previous = null;
        for (var candidate : selected) {
            if (candidate.sequence() >= evaluatedCount) {
                throw new IllegalArgumentException(
                        "selected sequence " + candidate.sequence() + " is outside evaluated range");
            }
            if (previous != null && !ranksBefore(previous, candidate)) {
                throw new IllegalArgumentException("selected candidates must be in rank order");
            }
            previous = candidate;
        }
        requireUniqueSequences(selected);
    }

    /** Returns how many candidates were scored. */
    public int evaluatedCount() {
        return evaluated.size();
    }

    /** Summarizes the result without dumping the candidate set. */
    @Override
    public String toString() {
        return "HypothesisEvaluation[evaluatedCount=" + evaluated.size()
                + ", selected=" + selected
                + ", requestedMaxSelected=" + requestedMaxSelected + "]";
    }

    /**
     * Pairwise for small selections (no allocation); above the limit, a sorted copy of the selected
     * sequences. Working state is bounded by the selection size, never by the candidate count.
     */
    private static void requireUniqueSequences(List<EvaluatedHypothesis> selected) {
        var size = selected.size();
        if (size <= PAIRWISE_LIMIT) {
            for (var i = 0; i < size; i++) {
                for (var j = i + 1; j < size; j++) {
                    if (selected.get(i).sequence() == selected.get(j).sequence()) {
                        throw duplicate(selected.get(i).sequence());
                    }
                }
            }
            return;
        }
        var sequences = new int[size];
        for (var i = 0; i < size; i++) {
            sequences[i] = selected.get(i).sequence();
        }
        Arrays.sort(sequences);
        for (var i = 1; i < size; i++) {
            if (sequences[i] == sequences[i - 1]) {
                throw duplicate(sequences[i]);
            }
        }
    }

    private static IllegalArgumentException duplicate(int sequence) {
        return new IllegalArgumentException("selected sequence " + sequence + " appears more than once");
    }

    private static boolean ranksBefore(EvaluatedHypothesis first, EvaluatedHypothesis second) {
        return HypothesisRanking.compare(
                first.breakdown().score(), first.sequence(),
                second.breakdown().score(), second.sequence()) < 0;
    }
}
