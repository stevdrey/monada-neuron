package monada.neuron.evaluation;

/**
 * Single definition of the candidate ranking rule, shared by every selector and by result
 * validation so the heap, the full-sort oracle, and {@link HypothesisEvaluation} cannot drift.
 *
 * <p>Candidates rank by score descending under {@link Double#compare} (so {@code +0.0} ranks
 * before {@code -0.0}), then by lower sequence first.
 */
final class HypothesisRanking {

    private HypothesisRanking() {
    }

    /** Returns a negative value when candidate A ranks before B, positive when after, else zero. */
    static int compare(double scoreA, int sequenceA, double scoreB, int sequenceB) {
        var byScore = Double.compare(scoreB, scoreA);
        return byScore != 0 ? byScore : Integer.compare(sequenceA, sequenceB);
    }

    /** Rejects NaN, which has no meaningful rank; infinities rank normally. */
    static void requireNotNaN(double score, int index) {
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("scores must not contain NaN, found at index " + index);
        }
    }

    /** Rejects any NaN in the array. */
    static void requireNoNaN(double[] scores) {
        for (var i = 0; i < scores.length; i++) {
            requireNotNaN(scores[i], i);
        }
    }
}
