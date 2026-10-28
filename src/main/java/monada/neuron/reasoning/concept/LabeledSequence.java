package monada.neuron.reasoning.concept;

import monada.neuron.spectral.SpectralPattern;

import java.util.List;
import java.util.Objects;

/**
 * Caller-owned, immutable, host-labeled training sequence: consecutive windows in stream order (contract
 * {@code ncm-wave-cognition/1} sections 8.2 and 12.3).
 *
 * <p>A {@link ConceptModelTrainer} reads each window's pattern for the spectral centroids and each consecutive pair
 * for the transition counts. The labels are host ground truth; nothing in Neuron produces or edits them. A sequence
 * is replayable: training twice on equal sequences yields equal models.
 *
 * @param windows the labeled windows, {@code 1..MAX_LENGTH}, in stream order
 */
public record LabeledSequence(List<Window> windows) {

    /** Largest number of windows in one sequence. */
    public static final int MAX_LENGTH = 4096;

    /** Copies the windows and checks the length. */
    public LabeledSequence {
        windows = List.copyOf(Objects.requireNonNull(windows, "windows must not be null"));
        if (windows.isEmpty() || windows.size() > MAX_LENGTH) {
            throw new IllegalArgumentException("a sequence needs 1.." + MAX_LENGTH + " windows, got: "
                    + windows.size());
        }
    }

    /**
     * One labeled window.
     *
     * @param pattern spectral evidence of the window
     * @param conceptCode host label of the concept that generated the window; must be a leaf of the hierarchy
     */
    public record Window(SpectralPattern pattern, long conceptCode) {

        /** Validates the pattern reference. */
        public Window {
            Objects.requireNonNull(pattern, "pattern must not be null");
        }
    }
}
