package monada.neuron.reasoning.concept;

import monada.neuron.spectral.DecompositionRejectionReason;
import monada.neuron.spectral.SynthesisStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * What {@link WaveCognitionReasoningStage} did with one window, at its position in the host input. Bounded
 * diagnostics: no samples, components or score tables are retained.
 */
public sealed interface WindowOutcome
        permits WindowOutcome.Incompatible, WindowOutcome.Rejected, WindowOutcome.Abstained, WindowOutcome.Predicted {

    /** Returns the window's position in the host input. */
    int position();

    /** Returns the comparisons the window cost, {@code 0} when it was not predicted. */
    default int comparisons() {
        return 0;
    }

    /**
     * The window's sample count, rate or unit differs from the model frame; it was not decomposed.
     *
     * @param position position in the host input
     */
    record Incompatible(int position) implements WindowOutcome {

        /** Validates the position. */
        public Incompatible {
            requirePosition(position);
        }
    }

    /**
     * The decomposer rejected the window.
     *
     * @param position position in the host input
     * @param reason the decomposer's typed reason
     */
    record Rejected(int position, DecompositionRejectionReason reason) implements WindowOutcome {

        /** Validates the components. */
        public Rejected {
            requirePosition(position);
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * The predictor abstained; no hypothesis came from the window.
     *
     * @param position position in the host input
     * @param reason the predictor's abstention reason
     * @param work the work of the prediction
     * @param reconstruction the reconstruction status when the check is enabled
     */
    record Abstained(int position, AbstentionReason reason, PredictionWork work,
                     Optional<SynthesisStatus> reconstruction) implements WindowOutcome {

        /** Validates the components. */
        public Abstained {
            requirePosition(position);
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(work, "work must not be null");
            Objects.requireNonNull(reconstruction, "reconstruction must not be null");
        }

        @Override
        public int comparisons() {
            return work.comparisons();
        }
    }

    /**
     * The predictor selected a concept, proposed as a hypothesis for the next window.
     *
     * @param position position in the host input
     * @param conceptOrdinal the selected concept's ordinal in the hierarchy
     * @param depth the selected concept's depth
     * @param score the selected concept's score in {@code [0, 1]}
     * @param refinementStop why the descent stopped above a leaf, or empty when it reached one
     * @param work the work of the prediction
     * @param reconstruction the reconstruction status when the check is enabled
     */
    record Predicted(int position, int conceptOrdinal, int depth, double score,
                     Optional<AbstentionReason> refinementStop, PredictionWork work,
                     Optional<SynthesisStatus> reconstruction) implements WindowOutcome {

        /** Validates the components. */
        public Predicted {
            requirePosition(position);
            if (conceptOrdinal < 0 || depth < 0) {
                throw new IllegalArgumentException("conceptOrdinal and depth must be non-negative");
            }
            if (!(score >= 0.0 && score <= 1.0)) {
                throw new IllegalArgumentException("score must be in [0, 1], got: " + score);
            }
            Objects.requireNonNull(refinementStop, "refinementStop must not be null");
            Objects.requireNonNull(work, "work must not be null");
            Objects.requireNonNull(reconstruction, "reconstruction must not be null");
        }

        @Override
        public int comparisons() {
            return work.comparisons();
        }
    }

    private static void requirePosition(int position) {
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative, got: " + position);
        }
    }
}
