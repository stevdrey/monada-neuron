package monada.neuron.context;

import monada.neuron.action.ActionStatus;
import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageStatus;

import java.util.Objects;
import java.util.UUID;

/** Stable event vocabulary for the deterministic, cycle-local diagnostic trace. */
public sealed interface CognitiveTraceEvent permits CognitiveTraceEvent.AeonInputStarted,
        CognitiveTraceEvent.NodeProcessed, CognitiveTraceEvent.SignalRouted,
        CognitiveTraceEvent.AeonInputCompleted, CognitiveTraceEvent.CognitiveStageStarted,
        CognitiveTraceEvent.CognitiveStageCompleted, CognitiveTraceEvent.CognitiveStageFailed,
        CognitiveTraceEvent.NodeAdapted, CognitiveTraceEvent.FeedbackConsumed {

    /** One configured Monad stage started with its inherited ordered input signals. */
    record CognitiveStageStarted(CognitiveStageKind stage) implements CognitiveTraceEvent {

        /** Validates the fixed stage position. */
        public CognitiveStageStarted {
            Objects.requireNonNull(stage, "stage must not be null");
        }
    }

    /** One configured Monad stage completed or reached a local execution limit. */
    record CognitiveStageCompleted(
            CognitiveStageKind stage,
            int inputSignalCount,
            int outputSignalCount,
            CognitiveStageStatus status) implements CognitiveTraceEvent {

        /** Validates deterministic stage counters and status. */
        public CognitiveStageCompleted {
            Objects.requireNonNull(stage, "stage must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (inputSignalCount < 0) {
                throw new IllegalArgumentException(
                        "inputSignalCount must be non-negative, got: " + inputSignalCount);
            }
            if (outputSignalCount < 0) {
                throw new IllegalArgumentException(
                        "outputSignalCount must be non-negative, got: " + outputSignalCount);
            }
        }
    }

    /** One configured Monad stage failed; the exception itself remains outside the trace. */
    record CognitiveStageFailed(CognitiveStageKind stage) implements CognitiveTraceEvent {

        /** Validates the fixed stage position. */
        public CognitiveStageFailed {
            Objects.requireNonNull(stage, "stage must not be null");
        }
    }

    /** An explicit input began coordination within an Aeon. */
    record AeonInputStarted(UUID aeonId, UUID startNodeId)
            implements CognitiveTraceEvent {

        /** Validates the input-start event. */
        public AeonInputStarted {
            Objects.requireNonNull(aeonId, "aeonId must not be null");
            Objects.requireNonNull(startNodeId, "startNodeId must not be null");
        }
    }

    /** A NodeProcessor call completed successfully. */
    record NodeProcessed(
            UUID nodeId,
            long inputSignalSequence,
            int completedStepCount,
            int emittedSignalCount) implements CognitiveTraceEvent {

        /** Validates the completed-processing event. */
        public NodeProcessed {
            Objects.requireNonNull(nodeId, "nodeId must not be null");
            if (inputSignalSequence < 0) {
                throw new IllegalArgumentException(
                        "inputSignalSequence must be non-negative, got: " + inputSignalSequence);
            }
            if (completedStepCount <= 0) {
                throw new IllegalArgumentException(
                        "completedStepCount must be positive, got: " + completedStepCount);
            }
            if (emittedSignalCount < 0) {
                throw new IllegalArgumentException(
                        "emittedSignalCount must be non-negative, got: " + emittedSignalCount);
            }
        }
    }

    /** A policy-accepted route produced an enqueued delivery occurrence. */
    record SignalRouted(
            UUID sourceNodeId,
            UUID targetNodeId,
            long emittedSignalSequence,
            long deliveredSignalSequence) implements CognitiveTraceEvent {

        /** Validates the accepted-route event. */
        public SignalRouted {
            Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
            Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");
            if (emittedSignalSequence < 0) {
                throw new IllegalArgumentException(
                        "emittedSignalSequence must be non-negative, got: "
                                + emittedSignalSequence);
            }
            if (deliveredSignalSequence < 0) {
                throw new IllegalArgumentException(
                        "deliveredSignalSequence must be non-negative, got: "
                                + deliveredSignalSequence);
            }
        }
    }

    /** One explicit Aeon input completed or was truncated by the cycle budget. */
    record AeonInputCompleted(
            UUID aeonId,
            UUID startNodeId,
            int processedSteps,
            boolean stepLimitReached,
            boolean hopLimitReached,
            boolean contextLimitReached) implements CognitiveTraceEvent {

        /** Validates the input-completion event. */
        public AeonInputCompleted {
            Objects.requireNonNull(aeonId, "aeonId must not be null");
            Objects.requireNonNull(startNodeId, "startNodeId must not be null");
            if (processedSteps < 0) {
                throw new IllegalArgumentException(
                        "processedSteps must be non-negative, got: " + processedSteps);
            }
        }
    }

    /** One Node was adapted by an adaptation policy during the cognitive cycle. */
    record NodeAdapted(
            UUID nodeId,
            boolean adapted,
            FrequencyState previousState,
            FrequencyState newState,
            double previousEnergy,
            double newEnergy) implements CognitiveTraceEvent {

        /** Validates the node adaptation event. */
        public NodeAdapted {
            Objects.requireNonNull(nodeId, "nodeId must not be null");
            Objects.requireNonNull(previousState, "previousState must not be null");
            Objects.requireNonNull(newState, "newState must not be null");
            if (!Double.isFinite(previousEnergy) || previousEnergy < 0) {
                throw new IllegalArgumentException(
                        "previousEnergy must be non-negative and finite, got: " + previousEnergy);
            }
            if (!Double.isFinite(newEnergy) || newEnergy < 0) {
                throw new IllegalArgumentException(
                        "newEnergy must be non-negative and finite, got: " + newEnergy);
            }
        }
    }

    /**
     * A prior cycle's feedback artifact was consumed by this cycle's adaptation stage.
     *
     * <p>The origin ordinal is the caller-assigned ordinal carried by the artifact and correlates this
     * consumption with the cycle that derived it. Every entry is exactly one of: adapted (the policy changed
     * a Node), unchanged (an eligible target the policy left as it was, such as under a no-op policy or at a
     * configured bound), or ineligible (its target is not among the stage's targets). The event keeps only
     * counters and enums, never the feedback entries themselves.
     */
    record FeedbackConsumed(
            long originCycleOrdinal,
            ActionStatus sourceStatus,
            FeedbackDisposition disposition,
            int entryCount,
            int adaptedCount,
            int unchangedCount,
            int ineligibleCount) implements CognitiveTraceEvent {

        /** Validates the consumption counters: every entry is adapted, unchanged, or ineligible. */
        public FeedbackConsumed {
            Objects.requireNonNull(sourceStatus, "sourceStatus must not be null");
            Objects.requireNonNull(disposition, "disposition must not be null");
            if (originCycleOrdinal < 0) {
                throw new IllegalArgumentException(
                        "originCycleOrdinal must be non-negative, got: " + originCycleOrdinal);
            }
            if (entryCount < 0 || adaptedCount < 0 || unchangedCount < 0 || ineligibleCount < 0) {
                throw new IllegalArgumentException(
                        "feedback counters must be non-negative, got: " + entryCount + "/" + adaptedCount
                                + "/" + unchangedCount + "/" + ineligibleCount);
            }
            if ((long) adaptedCount + unchangedCount + ineligibleCount != entryCount) {
                throw new IllegalArgumentException(
                        "adaptedCount + unchangedCount + ineligibleCount must equal entryCount, got: "
                                + adaptedCount + " + " + unchangedCount + " + " + ineligibleCount
                                + " != " + entryCount);
            }
        }
    }
}
