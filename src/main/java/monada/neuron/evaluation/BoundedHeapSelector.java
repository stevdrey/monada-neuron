package monada.neuron.evaluation;

import java.util.Objects;

/**
 * Selector that keeps the best K candidates in a primitive min-heap: O(N log K), K ints of
 * working memory, no boxing. The heap root is always the worst retained candidate. Candidates are
 * ordered by {@code HypothesisRanking}, the same rule {@link FullSortSelector} uses.
 */
public final class BoundedHeapSelector implements HypothesisSelector {

    @Override
    public int[] select(double[] scores, int k) {
        Objects.requireNonNull(scores, "scores must not be null");
        if (k < 0) {
            throw new IllegalArgumentException("k must not be negative, got: " + k);
        }
        var size = Math.min(k, scores.length);
        if (size == 0) {
            HypothesisRanking.requireNoNaN(scores);
            return new int[0];
        }
        var heap = new int[size];
        var count = 0;
        for (var i = 0; i < scores.length; i++) {
            if (count < size) {
                HypothesisRanking.requireNotNaN(scores[i], i);
                heap[count] = i;
                siftUp(heap, scores, count);
                count++;
            } else if (HypothesisRanking.compare(scores[i], i, scores[heap[0]], heap[0]) < 0) {
                // Indices ascend, so an equal score always ranks after the root: strictly-before suffices.
                // NaN compares greater than every number, so it always reaches this branch: checking
                // only on insertion rejects a NaN anywhere without a separate O(N) pass.
                HypothesisRanking.requireNotNaN(scores[i], i);
                heap[0] = i;
                siftDown(heap, scores, 0, size);
            }
        }
        // Move the worst to the tail repeatedly; the array ends up best-first.
        for (var end = size - 1; end > 0; end--) {
            var worst = heap[0];
            heap[0] = heap[end];
            heap[end] = worst;
            siftDown(heap, scores, 0, end);
        }
        return heap;
    }

    private void siftUp(int[] heap, double[] scores, int position) {
        var current = position;
        while (current > 0) {
            var parent = (current - 1) >>> 1;
            if (!worse(scores, heap[current], heap[parent])) {
                return;
            }
            swap(heap, current, parent);
            current = parent;
        }
    }

    private void siftDown(int[] heap, double[] scores, int position, int count) {
        var current = position;
        while (true) {
            var child = 2 * current + 1;
            if (child >= count) {
                return;
            }
            if (child + 1 < count && worse(scores, heap[child + 1], heap[child])) {
                child++;
            }
            if (!worse(scores, heap[child], heap[current])) {
                return;
            }
            swap(heap, current, child);
            current = child;
        }
    }

    /** Returns whether candidate {@code a} ranks after {@code b}. */
    private boolean worse(double[] scores, int a, int b) {
        return HypothesisRanking.compare(scores[a], a, scores[b], b) > 0;
    }

    private void swap(int[] heap, int first, int second) {
        var temp = heap[first];
        heap[first] = heap[second];
        heap[second] = temp;
    }
}
