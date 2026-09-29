package monada.neuron.resonance.adapter;

import monada.neuron.signal.Signal;

/** Translates recalled Resonance Store content back into a Neuron {@link Signal}. */
@FunctionalInterface
public interface RecalledSignalDecoder {

    /** Returns a deterministic Signal for the recalled content. */
    Signal decode(String content);
}
