package monada.neuron.evaluation;

import java.util.Objects;

/**
 * One scored candidate, referenced by its cycle-local {@code Hypothesis.sequence()} rather than
 * copied, so evaluation results stay small.
 *
 * @param sequence cycle-local candidate sequence in the evaluated {@code HypothesisSet}
 * @param breakdown inspectable score components
 */
public record EvaluatedHypothesis(int sequence, HypothesisScoreBreakdown breakdown) {

    /** Validates the candidate reference and breakdown. */
    public EvaluatedHypothesis {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative, got: " + sequence);
        }
        Objects.requireNonNull(breakdown, "breakdown must not be null");
    }
}
