package monada.neuron.monad;

import monada.neuron.context.CognitiveCycleSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * Propagates an operational stage failure together with the bounded failure snapshot it produced.
 */
public final class CognitiveCycleException extends RuntimeException {

    private final CognitiveStageKind failedStage;
    private final List<CognitiveStageResult> completedStageResults;
    private final CognitiveCycleSnapshot snapshot;

    /** Creates a failure that preserves the original cause and completed stage prefix. */
    public CognitiveCycleException(
            CognitiveStageKind failedStage,
            List<CognitiveStageResult> completedStageResults,
            CognitiveCycleSnapshot snapshot,
            RuntimeException cause) {
        super("cognitive cycle failed in stage: "
                + Objects.requireNonNull(failedStage, "failedStage must not be null"),
                Objects.requireNonNull(cause, "cause must not be null"));
        this.failedStage = failedStage;
        this.completedStageResults = List.copyOf(Objects.requireNonNull(
                completedStageResults,
                "completedStageResults must not be null"));
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
    }

    /** Returns the stage whose execution or trace handling failed. */
    public CognitiveStageKind failedStage() {
        return failedStage;
    }

    /** Returns the immutable successful-stage prefix completed before the failure. */
    public List<CognitiveStageResult> completedStageResults() {
        return completedStageResults;
    }

    /** Returns the bounded context snapshot completed with FAILURE. */
    public CognitiveCycleSnapshot snapshot() {
        return snapshot;
    }
}
