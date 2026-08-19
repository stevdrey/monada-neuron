package monada.neuron.memory;

import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/** Observable memory-recall stage result retaining its typed response and admitted output prefix. */
public record ResonanceMemoryStageResult(
        ResonanceMemoryResponse response,
        List<Signal> outputSignals) implements CognitiveStageResult {

    /** Validates that the recalled Signal suffix matches the typed response in adapter order. */
    public ResonanceMemoryStageResult {
        Objects.requireNonNull(response, "response must not be null");
        outputSignals = List.copyOf(Objects.requireNonNull(
                outputSignals,
                "outputSignals must not be null"));
        var resultCount = response.results().size();
        if (outputSignals.size() < resultCount) {
            throw new IllegalArgumentException("outputSignals must retain every recalled result");
        }
        var firstResultIndex = outputSignals.size() - resultCount;
        for (var index = 0; index < resultCount; index++) {
            if (!outputSignals.get(firstResultIndex + index).equals(response.results().get(index).signal())) {
                throw new IllegalArgumentException(
                        "outputSignals must end with recalled Signals in response order");
            }
        }
    }

    /** Returns the fixed memory-recall position in the canonical cycle. */
    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.MEMORY_RECALL;
    }

    /** Expected memory outcomes are non-fatal stage completions. */
    @Override
    public CognitiveStageStatus status() {
        return CognitiveStageStatus.COMPLETED;
    }

    /** Preserves response metadata while dropping every output candidate rejected by the cycle. */
    @Override
    public ResonanceMemoryStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        var admittedOutputs = List.copyOf(Objects.requireNonNull(
                admittedOutputSignals,
                "admittedOutputSignals must not be null"));
        if (admittedOutputs.size() > outputSignals.size()) {
            throw new IllegalArgumentException("admittedOutputSignals cannot exceed original output size");
        }
        for (var index = 0; index < admittedOutputs.size(); index++) {
            if (!outputSignals.get(index).equals(admittedOutputs.get(index))) {
                throw new IllegalArgumentException(
                        "admittedOutputSignals must be an ordered prefix of the original output");
            }
        }
        var passthroughCount = outputSignals.size() - response.results().size();
        var admittedResultCount = Math.max(0, admittedOutputs.size() - passthroughCount);
        return new ResonanceMemoryStageResult(
                response.withAdmittedResultPrefix(admittedResultCount),
                admittedOutputs);
    }
}
