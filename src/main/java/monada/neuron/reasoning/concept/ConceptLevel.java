package monada.neuron.reasoning.concept;

import java.util.List;
import java.util.Objects;

/**
 * The ranked candidates of one visited depth: score descending, then lower concept ordinal (contract section 8.4).
 * The first candidate is the one the descent selected or would have selected.
 *
 * @param depth the depth
 * @param ranked {@code 1..PredictionBudget.MAX_CANDIDATES_PER_LEVEL} candidates of that depth, in rank order
 */
public record ConceptLevel(int depth, List<ConceptCandidate> ranked) {

    /** Copies the candidates and validates depth and order. */
    public ConceptLevel {
        ranked = List.copyOf(Objects.requireNonNull(ranked, "ranked must not be null"));
        if (ranked.isEmpty() || ranked.size() > PredictionBudget.MAX_CANDIDATES_PER_LEVEL) {
            throw new IllegalArgumentException("a level holds 1.." + PredictionBudget.MAX_CANDIDATES_PER_LEVEL
                    + " candidates, got: " + ranked.size());
        }
        for (int i = 0; i < ranked.size(); i++) {
            ConceptCandidate candidate = ranked.get(i);
            if (candidate.depth() != depth) {
                throw new IllegalArgumentException("candidate " + i + " has depth " + candidate.depth()
                        + " at level " + depth);
            }
            if (i > 0 && NextConceptPredictor.rank(ranked.get(i - 1), candidate) >= 0) {
                throw new IllegalArgumentException("candidates must be ranked by score descending, then ordinal, "
                        + "at position " + i);
            }
        }
    }

    /** Returns the top candidate. */
    public ConceptCandidate top() {
        return ranked.getFirst();
    }
}
