package monada.neuron.routing.adaptation;

import monada.neuron.model.NodeType;

import java.util.Objects;

/**
 * The complete state every cohort Node starts from, and returns to on a reset (contract section 7).
 *
 * <p>Node history is always disabled ({@code historyLimit = 0}), so a cohort's state is exactly its four numeric
 * channels and a reset or restore can never leave a superseded effect behind.
 *
 * @param baselineAmplitude non-zero starting amplitude; the effective preference is {@code amplitude - baseline}
 * @param frequency starting frequency, finite and non-negative
 * @param phase starting phase, finite
 * @param energy starting energy, finite and non-negative
 * @param nodeType fixed type of every cohort Node
 */
public record InitialNodeState(
        double baselineAmplitude, double frequency, double phase, double energy, NodeType nodeType) {

    /** History limit of every cohort Node: history is disabled. */
    public static final int HISTORY_LIMIT = 0;

    /** Validates the channels. */
    public InitialNodeState {
        if (!Double.isFinite(baselineAmplitude) || baselineAmplitude <= 0.0) {
            throw new IllegalArgumentException(
                    "baselineAmplitude must be finite and positive, got: " + baselineAmplitude);
        }
        if (!Double.isFinite(frequency) || frequency < 0.0) {
            throw new IllegalArgumentException("frequency must be finite and non-negative, got: " + frequency);
        }
        if (!Double.isFinite(phase)) {
            throw new IllegalArgumentException("phase must be finite, got: " + phase);
        }
        if (!Double.isFinite(energy) || energy < 0.0) {
            throw new IllegalArgumentException("energy must be finite and non-negative, got: " + energy);
        }
        Objects.requireNonNull(nodeType, "nodeType must not be null");
    }
}
