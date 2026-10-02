package monada.neuron.evaluation;

/**
 * Bounded top-K selection over a score array indexed by candidate sequence.
 *
 * <p>Ranking is score descending, then lower index first. Scores must not be NaN. Implementations
 * must not mutate the array and must return identical output for identical input.
 */
public interface HypothesisSelector {

    /**
     * Returns the indices of the best {@code min(k, scores.length)} scores in rank order.
     *
     * @throws IllegalArgumentException if {@code k} is negative
     */
    int[] select(double[] scores, int k);
}
