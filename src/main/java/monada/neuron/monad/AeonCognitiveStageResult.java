package monada.neuron.monad;

import monada.neuron.aeon.AeonCoordinationResult;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Observable result of coordinating one registered Aeon inside a cognitive stage. */
public record AeonCognitiveStageResult(
        CognitiveStageKind kind,
        CognitiveStageStatus status,
        UUID aeonId,
        AeonCoordinationResult coordinationResult,
        List<Signal> outputSignals) implements CognitiveStageResult {

    /** Validates identity and snapshots ordered outputs. */
    public AeonCognitiveStageResult {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(aeonId, "aeonId must not be null");
        Objects.requireNonNull(coordinationResult, "coordinationResult must not be null");
        outputSignals = List.copyOf(Objects.requireNonNull(
                outputSignals,
                "outputSignals must not be null"));
    }
}
