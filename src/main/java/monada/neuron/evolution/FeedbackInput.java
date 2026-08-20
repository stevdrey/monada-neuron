package monada.neuron.evolution;

import monada.neuron.signal.Signal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable feedback input for one target node evaluated during or after cognitive processing.
 *
 * @param targetNodeId   identifier of the node to receive feedback (must not be null)
 * @param observedSignal optional observed signal emitted or processed by the node
 * @param targetSignal   optional target or expected signal for the node
 * @param score          scalar feedback score in {@code [-1.0, 1.0]} (negative = penalty, positive = reinforcement)
 */
public record FeedbackInput(
        UUID targetNodeId,
        Signal observedSignal,
        Signal targetSignal,
        double score) {

    /** Validates non-null target ID and finite bounded score in [-1.0, 1.0]. */
    public FeedbackInput {
        Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite, got: " + score);
        }
        if (score < -1.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be within [-1.0, 1.0], got: " + score);
        }
    }

    /** Returns the observed signal if present. */
    public Optional<Signal> optionalObservedSignal() {
        return Optional.ofNullable(observedSignal);
    }

    /** Returns the target signal if present. */
    public Optional<Signal> optionalTargetSignal() {
        return Optional.ofNullable(targetSignal);
    }

    /** Creates feedback with only a target node ID and scalar feedback score. */
    public static FeedbackInput ofScore(UUID targetNodeId, double score) {
        return new FeedbackInput(targetNodeId, null, null, score);
    }

    /** Creates feedback with a target node ID, expected target signal, and scalar feedback score. */
    public static FeedbackInput ofTarget(UUID targetNodeId, Signal targetSignal, double score) {
        return new FeedbackInput(targetNodeId, null, targetSignal, score);
    }

    /** Creates feedback with all components specified. */
    public static FeedbackInput of(
            UUID targetNodeId,
            Signal observedSignal,
            Signal targetSignal,
            double score) {
        return new FeedbackInput(targetNodeId, observedSignal, targetSignal, score);
    }
}
