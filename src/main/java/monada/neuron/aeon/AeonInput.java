package monada.neuron.aeon;

import monada.neuron.signal.Signal;

import java.util.Objects;
import java.util.UUID;

/**
 * One explicit Signal delivery into an Aeon.
 *
 * @param startNodeId identity of the canonical member where propagation begins
 * @param signal Signal presented to that member
 */
public record AeonInput(UUID startNodeId, Signal signal) {

    /** Validates the complete input boundary. */
    public AeonInput {
        Objects.requireNonNull(startNodeId, "startNodeId must not be null");
        Objects.requireNonNull(signal, "signal must not be null");
    }
}
