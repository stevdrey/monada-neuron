package monada.neuron.context;

import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.signal.Signal;

import java.util.Objects;
import java.util.UUID;

/**
 * One accepted signal occurrence owned by a cognitive cycle.
 *
 * <p>The immutable Signal value carries no execution identity. This envelope supplies a
 * deterministic sequence and the bounded local provenance required for diagnostics.
 */
public sealed interface CognitiveSignalOccurrence permits CognitiveSignalOccurrence.Input,
        CognitiveSignalOccurrence.Emitted, CognitiveSignalOccurrence.Delivered,
        CognitiveSignalOccurrence.StageInput, CognitiveSignalOccurrence.StageOutput {

    /** Returns the zero-based occurrence sequence within the cycle. */
    long sequence();

    /** Returns the immutable Signal carried by this occurrence. */
    Signal signal();

    /** A Signal accepted at the start node of one propagation. */
    record Input(long sequence, UUID startNodeId, Signal signal)
            implements CognitiveSignalOccurrence {

        /** Validates the input occurrence. */
        public Input {
            validateSequence(sequence);
            Objects.requireNonNull(startNodeId, "startNodeId must not be null");
            Objects.requireNonNull(signal, "signal must not be null");
        }
    }

    /** A Signal emitted by a completed node-processing operation. */
    record Emitted(long sequence, UUID sourceNodeId, Signal signal)
            implements CognitiveSignalOccurrence {

        /** Validates the emitted occurrence. */
        public Emitted {
            validateSequence(sequence);
            Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
            Objects.requireNonNull(signal, "signal must not be null");
        }
    }

    /** A Signal delivery accepted for enqueueing from one node to another. */
    record Delivered(long sequence, UUID sourceNodeId, UUID targetNodeId, Signal signal)
            implements CognitiveSignalOccurrence {

        /** Validates the delivered occurrence. */
        public Delivered {
            validateSequence(sequence);
            Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
            Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");
            Objects.requireNonNull(signal, "signal must not be null");
        }
    }

    /** A Signal admitted as input to one non-Aeon cognitive stage. */
    record StageInput(long sequence, CognitiveStageKind stage, Signal signal)
            implements CognitiveSignalOccurrence {

        /** Validates the stage-input occurrence. */
        public StageInput {
            validateSequence(sequence);
            Objects.requireNonNull(stage, "stage must not be null");
            Objects.requireNonNull(signal, "signal must not be null");
        }
    }

    /** A Signal admitted as output from one non-Aeon cognitive stage. */
    record StageOutput(long sequence, CognitiveStageKind stage, Signal signal)
            implements CognitiveSignalOccurrence {

        /** Validates the stage-output occurrence. */
        public StageOutput {
            validateSequence(sequence);
            Objects.requireNonNull(stage, "stage must not be null");
            Objects.requireNonNull(signal, "signal must not be null");
        }
    }

    private static void validateSequence(long sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative, got: " + sequence);
        }
    }
}
