package monada.neuron.evolution;

import monada.neuron.signal.Signal;

import java.util.Objects;
import java.util.UUID;

/**
 * One bounded feedback value for a single target Node.
 *
 * <p>The target is referenced by its stable Node identifier, never by the Node itself, so a pending
 * feedback artifact does not retain the object graph of the cycle that produced it.
 *
 * @param targetNodeId identifier of the Node that should receive the feedback
 * @param targetSignal optional expected Signal for the adaptation rule; {@code null} for a scalar score
 * @param score finite, non-zero feedback in {@code [-1.0, 1.0]}; the sign must match the artifact's disposition
 */
public record FeedbackEntry(UUID targetNodeId, Signal targetSignal, double score) {

    /** Validates the target and the finite non-zero score. */
    public FeedbackEntry {
        Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite, got: " + score);
        }
        if (score < -1.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be within [-1.0, 1.0], got: " + score);
        }
        if (score == 0.0) {
            throw new IllegalArgumentException("score must not be zero; use a NEUTRAL disposition instead");
        }
    }

    /** Creates a scalar entry without a target Signal. */
    public static FeedbackEntry of(UUID targetNodeId, double score) {
        return new FeedbackEntry(targetNodeId, null, score);
    }

    /** Converts this entry to the input consumed by an {@link AdaptationPolicy}. */
    public FeedbackInput toFeedbackInput() {
        return targetSignal == null
                ? FeedbackInput.ofScore(targetNodeId, score)
                : FeedbackInput.ofTarget(targetNodeId, targetSignal, score);
    }
}
