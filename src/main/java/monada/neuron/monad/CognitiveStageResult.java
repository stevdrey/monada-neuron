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

    /**
     * Returns this result with the cycle-admitted output prefix.
     *
     * <p>The deterministic cycle owns signal-budget admission for extension stages. Implementations
     * that retain typed metadata may override this method, but must not retain rejected output
     * candidates in the returned result.
     */
    default CognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        return new CognitiveStageResultSnapshot(kind(), status(), admittedOutputSignals);
    }
}
