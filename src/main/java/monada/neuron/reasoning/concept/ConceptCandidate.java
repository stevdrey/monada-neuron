package monada.neuron.reasoning.concept;

import java.util.List;
import java.util.Objects;

/**
 * One ranked hypothesis at one depth (contract {@code ncm-wave-cognition/1} sections 8.3 and 8.5): a proposal with its
 * evidence and score, never a truth.
 *
 * @param conceptOrdinal ordinal of the concept in the hierarchy
 * @param depth depth of the concept
 * @param score finite score in {@code [0, 1]}; a probability only where held-out calibration supports it
 * @param margin score minus the best other candidate's score at the same depth, in {@code [-1, 1]}; the top
 *     candidate's margin is its lead over the runner-up, and a lone candidate's margin is its score
 * @param supportingComponents positions in the evidence pattern of the components that contributed most to the
 *     concept's spectral similarity, strongest first, at most {@link NextConceptPredictor#MAX_SUPPORTING_COMPONENTS}
 */
public record ConceptCandidate(int conceptOrdinal, int depth, double score, double margin,
                               List<Integer> supportingComponents) {

    /** Validates the ranges and copies the component list. */
    public ConceptCandidate {
        if (conceptOrdinal < 0 || conceptOrdinal >= ConceptHierarchy.MAX_CONCEPTS) {
            throw new IllegalArgumentException("conceptOrdinal out of range: " + conceptOrdinal);
        }
        if (depth < 0 || depth >= ConceptHierarchy.MAX_DEPTH) {
            throw new IllegalArgumentException("depth out of range: " + depth);
        }
        if (!(score >= 0.0 && score <= 1.0)) {
            throw new IllegalArgumentException("score must be in [0, 1], got: " + score);
        }
        if (!(margin >= -1.0 && margin <= 1.0)) {
            throw new IllegalArgumentException("margin must be in [-1, 1], got: " + margin);
        }
        supportingComponents = List.copyOf(Objects.requireNonNull(supportingComponents,
                "supportingComponents must not be null"));
        if (supportingComponents.size() > NextConceptPredictor.MAX_SUPPORTING_COMPONENTS) {
            throw new IllegalArgumentException("at most " + NextConceptPredictor.MAX_SUPPORTING_COMPONENTS
                    + " supporting components, got: " + supportingComponents.size());
        }
        for (int position : supportingComponents) {
            if (position < 0) {
                throw new IllegalArgumentException("component positions must be non-negative, got: " + position);
            }
        }
    }
}
