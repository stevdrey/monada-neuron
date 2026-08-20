package monada.neuron.evolution;

import monada.neuron.model.FrequencyState;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable decision record documenting the outcome of an adaptation operation on a node.
 *
 * @param nodeId         identifier of the evaluated node (must not be null)
 * @param adapted        whether the node state or energy was modified
 * @param previousState  the frequency state prior to adaptation (must not be null)
 * @param newState       the frequency state after adaptation (must not be null)
 * @param previousEnergy the energy level prior to adaptation (must be non-negative and finite)
 * @param newEnergy      the energy level after adaptation (must be non-negative and finite)
 */
public record AdaptationDecision(
        UUID nodeId,
        boolean adapted,
        FrequencyState previousState,
        FrequencyState newState,
        double previousEnergy,
        double newEnergy) {

    /** Validates all components are non-null and numeric values are finite and non-negative. */
    public AdaptationDecision {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        if (!Double.isFinite(previousEnergy) || previousEnergy < 0) {
            throw new IllegalArgumentException(
                    "previousEnergy must be non-negative and finite, got: " + previousEnergy);
        }
        if (!Double.isFinite(newEnergy) || newEnergy < 0) {
            throw new IllegalArgumentException(
                    "newEnergy must be non-negative and finite, got: " + newEnergy);
        }
    }
}
