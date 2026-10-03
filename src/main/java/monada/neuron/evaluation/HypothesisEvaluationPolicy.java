package monada.neuron.evaluation;

import monada.neuron.reasoning.HypothesisSet;

/**
 * Neuron-owned contract that scores bounded hypothesis candidates and selects a ranked subset.
 *
 * <p>Implementations must be pure: no mutation of Nodes, adaptation state, the supplied set, or
 * any shared state, and identical inputs and configuration must produce equal results. Ranking is
 * score descending, then lower {@code Hypothesis.sequence()} first. This contract covers
 * cognitive evaluation of hypotheses; it is not memory retrieval ranking, which belongs to the
 * Resonance Store.
 */
public interface HypothesisEvaluationPolicy {

    /**
     * Evaluates every candidate and returns at most {@code maxSelected} ranked candidates.
     *
     * @param hypotheses candidates in deterministic cycle order
     * @param maxSelected upper bound on selected candidates; zero selects none
     * @throws IllegalArgumentException if {@code maxSelected} is negative
     */
    HypothesisEvaluation evaluate(HypothesisSet hypotheses, int maxSelected);
}
