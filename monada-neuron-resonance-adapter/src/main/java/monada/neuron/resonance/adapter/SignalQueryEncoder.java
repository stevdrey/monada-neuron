package monada.neuron.resonance.adapter;

import monada.neuron.signal.Signal;

/** Translates one Neuron {@link Signal} into the text query understood by the Resonance Store. */
@FunctionalInterface
public interface SignalQueryEncoder {

    /** Returns a deterministic, non-blank query for the Signal. */
    String encode(Signal signal);
}
