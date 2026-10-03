package monada.neuron.evaluation;

import java.util.Arrays;
import java.util.Objects;

/** Reference selector that ranks all candidates with a full sort: O(N log N), simple, boxed. */
public final class FullSortSelector implements HypothesisSelector {

    @Override
    public int[] select(double[] scores, int k) {
        Objects.requireNonNull(scores, "scores must not be null");
        if (k < 0) {
            throw new IllegalArgumentException("k must not be negative, got: " + k);
        }
        HypothesisRanking.requireNoNaN(scores);
        var size = Math.min(k, scores.length);
        if (size == 0) {
            return new int[0];
        }
        var order = new Integer[scores.length];
        for (var i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (left, right) -> HypothesisRanking.compare(scores[left], left, scores[right], right));
        var result = new int[size];
        for (var i = 0; i < size; i++) {
            result[i] = order[i];
        }
        return result;
    }
}
