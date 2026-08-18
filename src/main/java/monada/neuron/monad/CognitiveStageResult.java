package monada.neuron.monad;

import monada.neuron.signal.Signal;

import java.util.List;

/** Immutable observable result emitted by one executed cognitive stage. */
public interface CognitiveStageResult {

    /** Returns the fixed pipeline position represented by this result. */
    CognitiveStageKind kind();

    /** Returns whether the stage completed or was truncated by a local execution limit. */
    CognitiveStageStatus status();

    /** Returns ordered signals offered to the next configured stage. */
    List<Signal> outputSignals();
}
