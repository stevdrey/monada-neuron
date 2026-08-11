package monada.neuron.signal;

/** Classifies a signal by its role in the cognitive flow rather than by data modality. */
public enum SignalKind {

    /** An observation entering the cognitive system from an input boundary. */
    OBSERVATION,

    /** An internal value emitted between cognitive processing stages. */
    INTERMEDIATE,

    /** Information describing an evaluated outcome for later adaptation. */
    FEEDBACK
}
