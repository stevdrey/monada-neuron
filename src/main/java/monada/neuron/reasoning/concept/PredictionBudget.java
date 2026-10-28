package monada.neuron.reasoning.concept;

/**
 * Work bounds of one {@link NextConceptPredictor#predict} call (contract {@code ncm-wave-cognition/1} section 11.1).
 *
 * @param maxCandidatesPerLevel ranked candidates kept per visited depth, {@code 1..MAX_CANDIDATES_PER_LEVEL}; lower
 *     ranked candidates are scored but not reported
 * @param maxComparisons largest number of centroid comparisons the call may make; a model that needs more abstains
 *     with {@link AbstentionReason#BUDGET_EXHAUSTED} before any comparison
 * @param maxLevels largest number of depths the descent may visit, {@code 1..ConceptHierarchy.MAX_DEPTH}; a descent
 *     cut by it stops coarse-only with {@link AbstentionReason#BUDGET_EXHAUSTED} as its refinement stop
 */
public record PredictionBudget(int maxCandidatesPerLevel, int maxComparisons, int maxLevels) {

    /** Largest {@code maxCandidatesPerLevel} (contract section 11.1, {@code maxCandidatesPerLevel}). */
    public static final int MAX_CANDIDATES_PER_LEVEL = 16;

    /** Bounds that admit every valid hierarchy. */
    public static final PredictionBudget DEFAULT = new PredictionBudget(MAX_CANDIDATES_PER_LEVEL,
            ConceptHierarchy.MAX_CONCEPTS, ConceptHierarchy.MAX_DEPTH);

    /** Validates the bounds. */
    public PredictionBudget {
        if (maxCandidatesPerLevel < 1 || maxCandidatesPerLevel > MAX_CANDIDATES_PER_LEVEL) {
            throw new IllegalArgumentException("maxCandidatesPerLevel must be in [1, " + MAX_CANDIDATES_PER_LEVEL
                    + "], got: " + maxCandidatesPerLevel);
        }
        if (maxComparisons < 1) {
            throw new IllegalArgumentException("maxComparisons must be positive, got: " + maxComparisons);
        }
        if (maxLevels < 1 || maxLevels > ConceptHierarchy.MAX_DEPTH) {
            throw new IllegalArgumentException("maxLevels must be in [1, " + ConceptHierarchy.MAX_DEPTH
                    + "], got: " + maxLevels);
        }
    }
}
