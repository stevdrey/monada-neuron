package monada.neuron.reasoning.concept;

import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.spectral.WaveProvenance;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Caller-built record of one next-concept prediction, the attribution anchor of a {@link ConceptOutcome} (contract
 * {@code ncm-wave-cognition/2} section 10.2, ADR 0045). It names the exact snapshot by {@link
 * ConceptModel#fingerprint()}, the window by its {@link WaveProvenance} source and offset, the previous concept the
 * query carried and the selected concept. Neuron retains no receipt: the caller keeps it until the outcome is known.
 *
 * <p>A receipt is evidence of what was predicted, never of what happened: {@link ConceptFeedbackPolicy} learns only
 * from the verified label of the outcome, so a receipt cannot carry feedback derived from the predictor's own output.
 *
 * @param modelFingerprint the fingerprint of the snapshot that predicted, 64 lower-case hex characters
 * @param sourceReference the window's opaque source reference
 * @param windowOffset the window's offset in the host stream, at least {@code 0}
 * @param previousConceptCode the previous concept the query carried, or empty
 * @param predictedCode the selected concept's code
 * @param depth the selected concept's depth, in {@code [0, ConceptHierarchy.MAX_DEPTH)}
 */
public record ConceptPredictionReceipt(String modelFingerprint, String sourceReference, long windowOffset,
                                       OptionalLong previousConceptCode, long predictedCode, int depth) {

    /** Validates the components. */
    public ConceptPredictionReceipt {
        Objects.requireNonNull(modelFingerprint, "modelFingerprint must not be null");
        if (modelFingerprint.length() != 64 || !modelFingerprint.chars().allMatch(ConceptPredictionReceipt::hexDigit)) {
            throw new IllegalArgumentException("modelFingerprint must be 64 lower-case hex characters");
        }
        Objects.requireNonNull(sourceReference, "sourceReference must not be null");
        if (sourceReference.isBlank() || sourceReference.length() > ResonanceMemoryResult.MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("sourceReference must be non-blank and at most "
                    + ResonanceMemoryResult.MAX_REFERENCE_LENGTH + " characters");
        }
        if (windowOffset < 0) {
            throw new IllegalArgumentException("windowOffset must be non-negative, got: " + windowOffset);
        }
        Objects.requireNonNull(previousConceptCode, "previousConceptCode must not be null");
        if (depth < 0 || depth >= ConceptHierarchy.MAX_DEPTH) {
            throw new IllegalArgumentException("depth must be in [0, " + ConceptHierarchy.MAX_DEPTH + "), got: "
                    + depth);
        }
    }

    /**
     * Builds the receipt of a library prediction.
     *
     * @param model the snapshot that predicted
     * @param query the query it answered
     * @param prediction its candidates
     * @return the receipt
     */
    public static ConceptPredictionReceipt of(ConceptModel model, ConceptQuery query,
                                              ConceptPrediction.ConceptCandidates prediction) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(prediction, "prediction must not be null");
        ConceptCandidate selected = prediction.selected();
        return of(model, query.evidence().provenance(), query.previousConceptCode(), selected.conceptOrdinal(),
                selected.depth());
    }

    /**
     * Builds the receipt of a window that {@link WaveCognitionReasoningStage} predicted.
     *
     * @param model the snapshot the stage ran with
     * @param window the host window
     * @param outcome the stage's outcome for that window
     * @return the receipt
     */
    public static ConceptPredictionReceipt of(ConceptModel model, WaveWindow window, WindowOutcome.Predicted outcome) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        return of(model, window.wave().provenance(), window.previousConceptCode(), outcome.conceptOrdinal(),
                outcome.depth());
    }

    private static ConceptPredictionReceipt of(ConceptModel model, WaveProvenance provenance, OptionalLong previous,
                                               int ordinal, int depth) {
        long code = model.hierarchy().node(ordinal).conceptCode();
        return new ConceptPredictionReceipt(model.fingerprint(), provenance.sourceReference(),
                provenance.windowOffset(), previous, code, depth);
    }

    private static boolean hexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }
}
