package monada.neuron.reasoning.concept;

/**
 * Work one prediction performed, reported with every result so cost is measured, not assumed.
 *
 * @param comparisons centroid comparisons, each O(bins)
 * @param candidatesScored candidates ranked over every visited depth
 * @param levelsVisited depths the descent visited
 */
public record PredictionWork(int comparisons, int candidatesScored, int levelsVisited) {

    /** No work: the call stopped before scoring. */
    public static final PredictionWork NONE = new PredictionWork(0, 0, 0);

    /** Validates the counts. */
    public PredictionWork {
        if (comparisons < 0 || candidatesScored < 0 || levelsVisited < 0) {
            throw new IllegalArgumentException("work counts must be non-negative, got: " + comparisons + ", "
                    + candidatesScored + ", " + levelsVisited);
        }
    }
}
