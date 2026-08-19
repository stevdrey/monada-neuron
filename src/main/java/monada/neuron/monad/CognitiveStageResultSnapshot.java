package monada.neuron.monad;

import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/** Immutable normalized result of an extension stage after cycle-owned signal admission. */
public record CognitiveStageResultSnapshot(
        CognitiveStageKind kind,
        CognitiveStageStatus status,
        List<Signal> outputSignals) implements CognitiveStageResult {

    /** Validates stage identity and snapshots the admitted deterministic output prefix. */
    public CognitiveStageResultSnapshot {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(status, "status must not be null");
        outputSignals = List.copyOf(Objects.requireNonNull(
                outputSignals,
                "outputSignals must not be null"));
    }
}
