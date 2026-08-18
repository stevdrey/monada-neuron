package monada.neuron.monad;

import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.context.CognitiveCycleSnapshot;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable successful result of one complete or intentionally terminated cognitive cycle. */
public record CognitiveCycleResult(
        UUID monadId,
        CognitiveCycleTermination termination,
        List<CognitiveStageResult> stageResults,
        List<Signal> outputSignals,
        CognitiveCycleSnapshot snapshot) {

    /** Validates cycle identity, snapshots ordered values, and requires a successful context exit. */
    public CognitiveCycleResult {
        Objects.requireNonNull(monadId, "monadId must not be null");
        Objects.requireNonNull(termination, "termination must not be null");
        stageResults = List.copyOf(Objects.requireNonNull(
                stageResults,
                "stageResults must not be null"));
        outputSignals = List.copyOf(Objects.requireNonNull(
                outputSignals,
                "outputSignals must not be null"));
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (snapshot.outcome() != CognitiveCycleOutcome.SUCCESS) {
            throw new IllegalArgumentException(
                    "successful cycle results require a SUCCESS snapshot outcome");
        }
    }
}
