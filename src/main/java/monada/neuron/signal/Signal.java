package monada.neuron.signal;

import monada.neuron.model.FrequencyState;

import java.util.Objects;

/**
 * Immutable information carrier inside an active Monada Neuron cognitive flow.
 *
 * <p>A signal has no durable or cycle-local identity. Correlation and tracing, when required,
 * belong to the surrounding cognitive context rather than to this value. Equality remains
 * structural across the kind and frequency state.
 *
 * @param kind cognitive role of the signal
 * @param frequencyState immutable wave-like signal content; its amplitude represents intensity
 */
public record Signal(SignalKind kind, FrequencyState frequencyState) {

    /** Validates all required signal components. */
    public Signal {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(frequencyState, "frequencyState must not be null");
    }
}
