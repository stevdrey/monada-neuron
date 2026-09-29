package monada.neuron.resonance.adapter;

import monada.neuron.signal.Signal;

import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic interim encoder that renders a Signal as canonical text.
 *
 * <p>This is a placeholder for a semantic mapping: it makes recall reproducible but carries no
 * linguistic meaning. Supply a domain encoder through {@link ResonanceStoreAdapterConfig}.
 */
public final class CanonicalSignalQueryEncoder implements SignalQueryEncoder {

    /** Returns {@code kind amplitude frequency phase} using {@link Double#toString(double)}. */
    @Override
    public String encode(Signal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        var state = signal.frequencyState();
        return signal.kind().name().toLowerCase(Locale.ROOT)
                + " " + state.amplitude()
                + " " + state.frequency()
                + " " + state.phase();
    }
}
