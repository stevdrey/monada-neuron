package monada.neuron.context;

import java.util.Objects;
import java.util.UUID;

/** Stable event vocabulary for the deterministic, cycle-local diagnostic trace. */
public sealed interface CognitiveTraceEvent permits CognitiveTraceEvent.AeonInputStarted,
        CognitiveTraceEvent.NodeProcessed, CognitiveTraceEvent.SignalRouted,
        CognitiveTraceEvent.AeonInputCompleted {

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
}
