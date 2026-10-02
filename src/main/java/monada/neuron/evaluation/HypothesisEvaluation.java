package monada.neuron.evaluation;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of evaluating a candidate set: the selected candidates in rank order.
 *
 * <p>Rank order is score descending under {@link Double#compare}, then lower sequence first, and
 * each candidate appears at most once. Only selected candidates are
 * retained; {@code evaluatedCount} records how many candidates were scored.
 *
 * @param selected ranked selected candidates
 * @param evaluatedCount number of candidates scored
 * @param requestedMaxSelected the selection bound requested by the caller
 */
public record HypothesisEvaluation(
        List<EvaluatedHypothesis> selected,
        int evaluatedCount,
        int requestedMaxSelected) {

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
        var seen = new BitSet();
        for (var candidate : selected) {
            if (candidate.sequence() >= evaluatedCount) {
                throw new IllegalArgumentException(
                        "selected sequence " + candidate.sequence() + " is outside evaluated range");
            }
            if (seen.get(candidate.sequence())) {
                throw new IllegalArgumentException(
                        "selected sequence " + candidate.sequence() + " appears more than once");
            }
            seen.set(candidate.sequence());
            if (previous != null && !ranksBefore(previous, candidate)) {
                throw new IllegalArgumentException("selected candidates must be in rank order");
            }
            previous = candidate;
        }
    }

    private static boolean ranksBefore(EvaluatedHypothesis first, EvaluatedHypothesis second) {
        var byScore = Double.compare(first.breakdown().score(), second.breakdown().score());
        return byScore > 0 || (byScore == 0 && first.sequence() < second.sequence());
    }
}
