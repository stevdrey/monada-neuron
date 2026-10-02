package monada.neuron.evaluation;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of evaluating a candidate set: the selected candidates in rank order.
 *
 * <p>Rank order is defined by {@code HypothesisRanking} (score descending, then lower sequence
 * first) and each candidate appears at most once. Only selected candidates are retained;
 * {@code evaluatedCount} records how many candidates were scored.
 *
 * @param selected ranked selected candidates
 * @param evaluatedCount number of candidates scored
 * @param requestedMaxSelected the selection bound requested by the caller
 */
public record HypothesisEvaluation(
        List<EvaluatedHypothesis> selected,
        int evaluatedCount,
        int requestedMaxSelected) {

    private static final int PAIRWISE_LIMIT = 16;

    /** Validates counts, sequence range, and the documented rank order. */
    public HypothesisEvaluation {
        selected = List.copyOf(Objects.requireNonNull(selected, "selected must not be null"));
        if (evaluatedCount < 0) {
            throw new IllegalArgumentException("evaluatedCount must not be negative, got: " + evaluatedCount);
        }
        if (requestedMaxSelected < 0) {
            throw new IllegalArgumentException(
                    "requestedMaxSelected must not be negative, got: " + requestedMaxSelected);
        }
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
        requireUniqueSequences(selected, evaluatedCount);
    }

    /** Pairwise for small selections (no allocation); a bit set above the limit. */
    private static void requireUniqueSequences(List<EvaluatedHypothesis> selected, int evaluatedCount) {
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
        var seen = new BitSet(evaluatedCount);
        for (var candidate : selected) {
            if (seen.get(candidate.sequence())) {
                throw duplicate(candidate.sequence());
            }
            seen.set(candidate.sequence());
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
