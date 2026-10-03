package monada.neuron.evolution;

import monada.neuron.reasoning.Proposition;

import java.util.Objects;

/**
 * Compact record of a hypothesis that was selected by evaluation in the cycle that produced feedback.
 *
 * <p>The hypothesis is identified by its {@link Proposition}, which is structural and stable across
 * cycles, instead of the cycle-local {@code Hypothesis.sequence}. Neither the hypothesis set nor its
 * evidence is retained.
 *
 * @param proposition statement the selected hypothesis asserted
 * @param evaluationScore finite evaluation score in {@code [0.0, 1.0]}
 */
public record HypothesisAttribution(Proposition proposition, double evaluationScore) {

    /** Validates the proposition and the finite bounded score. */
    public HypothesisAttribution {
        Objects.requireNonNull(proposition, "proposition must not be null");
        if (!Double.isFinite(evaluationScore)) {
            throw new IllegalArgumentException("evaluationScore must be finite, got: " + evaluationScore);
        }
        if (evaluationScore < 0.0 || evaluationScore > 1.0) {
            throw new IllegalArgumentException(
                    "evaluationScore must be within [0.0, 1.0], got: " + evaluationScore);
        }
    }
}
