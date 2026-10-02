package monada.neuron.evaluation;

/**
 * Bounded top-K selection over a score array indexed by candidate sequence.
 *
 * <p>Ranking is defined once by {@code HypothesisRanking}: score descending under
 * {@link Double#compare} (so {@code +0.0} ranks before {@code -0.0}), then lower index first.
 * Infinities rank normally; NaN has no rank and is rejected with {@link IllegalArgumentException}. Implementations
 * must not mutate the array and must return identical output for identical input.
 */
public interface HypothesisSelector {

    /**
     * Returns the indices of the best {@code min(k, scores.length)} scores in rank order.
     *
     * @throws IllegalArgumentException if {@code k} is negative or a score is NaN
     */
    int[] select(double[] scores, int k);
}
