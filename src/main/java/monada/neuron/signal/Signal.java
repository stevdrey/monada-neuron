package monada.neuron.signal;

import monada.neuron.model.FrequencyState;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable information carrier inside an active Monada Neuron cognitive flow.
 *
 * <p>The caller supplies the UUID explicitly. The model performs no random generation and does
 * not imply that the signal is persisted beyond its active cognitive lifecycle. Equality remains
 * structural: the UUID, kind, and frequency state all participate in record equality.
 *
 * @param id caller-assigned identifier used for correlation and tracing
 * @param kind cognitive role of the signal
 * @param frequencyState immutable wave-like signal content; its amplitude represents intensity
 */
public record Signal(UUID id, SignalKind kind, FrequencyState frequencyState) {

    /** Validates all required signal components. */
    public Signal {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(frequencyState, "frequencyState must not be null");
    }
}
