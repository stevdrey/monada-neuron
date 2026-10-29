package monada.neuron.reasoning.concept;

import monada.neuron.reasoning.HypothesisLimits;

import java.util.Objects;

/**
 * Work bounds of one {@link WaveCognitionReasoningStage} execution (contract {@code ncm-wave-cognition/2} section
 * 11.1).
 *
 * @param maxWindows largest number of windows one execution accepts, {@code 1..MAX_WINDOWS}; an input with more is
 *     rejected whole, never truncated
 * @param maxTotalComparisons largest number of centroid comparisons summed over the execution's predictions; the
 *     window that would exceed it is not processed and the stage reports a reached limit. It bounds prediction work
 *     only: transform work is bounded by {@code maxWindows}, at most one DFT of the model's sample count and one
 *     synthesis per window, and is counted in the {@link WaveCognitionReport}
 * @param predictionBudget bounds of each {@link NextConceptPredictor#predict} call
 * @param hypothesisLimits bounds of the hypothesis set handed to {@code EVALUATION}
 * @param verifyReconstruction whether each decomposed window is synthesized back and its fidelity status reported;
 *     a diagnostic that never changes a hypothesis
 */
public record WaveCognitionLimits(int maxWindows, int maxTotalComparisons, PredictionBudget predictionBudget,
                                  HypothesisLimits hypothesisLimits, boolean verifyReconstruction) {

    /** Largest {@code maxWindows} (contract section 11.1, {@code maxWindowsPerCycle}). */
    public static final int MAX_WINDOWS = 32;

    /** Eight windows, 2,048 comparisons, default prediction and hypothesis bounds, no reconstruction check. */
    public static final WaveCognitionLimits DEFAULT = new WaveCognitionLimits(8, 2_048, PredictionBudget.DEFAULT,
            HypothesisLimits.DEFAULT, false);

    /** Validates the bounds. */
    public WaveCognitionLimits {
        if (maxWindows < 1 || maxWindows > MAX_WINDOWS) {
            throw new IllegalArgumentException("maxWindows must be in [1, " + MAX_WINDOWS + "], got: " + maxWindows);
        }
        if (maxTotalComparisons < 1) {
            throw new IllegalArgumentException("maxTotalComparisons must be positive, got: " + maxTotalComparisons);
        }
        Objects.requireNonNull(predictionBudget, "predictionBudget must not be null");
        Objects.requireNonNull(hypothesisLimits, "hypothesisLimits must not be null");
    }

    /** Returns these limits with the reconstruction check enabled or disabled. */
    public WaveCognitionLimits withVerifyReconstruction(boolean verify) {
        return new WaveCognitionLimits(maxWindows, maxTotalComparisons, predictionBudget, hypothesisLimits, verify);
    }
}
