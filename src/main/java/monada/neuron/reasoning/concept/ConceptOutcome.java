package monada.neuron.reasoning.concept;

import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.spectral.SpectralPattern;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Caller-carried outcome of one next-concept prediction: the explicit feedback artifact of contract {@code
 * ncm-wave-cognition/2} section 10.2 (ADR 0021 cross-cycle rule, ADR 0049). The host's verifier, never the predictor,
 * decides what happened at the predicted step and says so with a {@link Verification}.
 *
 * <p>A {@link Verification#VERIFIED} outcome carries the observed leaf, the spectral pattern of the window that was
 * labeled with it, and optionally the verified leaf of the window before it (which adds one transition count). The
 * other verifications carry no label and lead to abstention. The {@link #polarity(ConceptHierarchy)} compares the verified leaf with
 * the receipt and is reported, but {@link ConceptFeedbackPolicy} learns from the verified label alone, so a confirmed
 * prediction adds exactly the evidence an independently labeled window would.
 *
 * @param receipt the prediction the outcome refers to
 * @param verification what the verifier established
 * @param verifiedCode the observed leaf code, present exactly when {@code VERIFIED}
 * @param observedEvidence the labeled window's pattern, present exactly when {@code VERIFIED}
 * @param verifiedPreviousCode the verified leaf code of the preceding window, or empty; only when {@code VERIFIED}
 * @param originCycleOrdinal the caller's ordinal of the cycle that predicted, at least {@code 0}
 * @param verifierId the opaque identity of the host verifier
 */
public record ConceptOutcome(ConceptPredictionReceipt receipt, Verification verification, OptionalLong verifiedCode,
                             SpectralPattern observedEvidence, OptionalLong verifiedPreviousCode,
                             long originCycleOrdinal, String verifierId) {

    /** What a host verifier established about a predicted step. */
    public enum Verification {
        /** The next concept was observed and verified. */
        VERIFIED,
        /** Nothing was verified (no ground truth yet, or the verifier could not decide). */
        UNVERIFIED,
        /** The verifier holds contradicting evidence about the step. */
        CONTRADICTED
    }

    /** How a verified outcome relates to the prediction; reported, never a learning signal by itself. */
    public enum Polarity {
        /** The verified leaf is the predicted concept or lies in its subtree. */
        POSITIVE,
        /** The verified leaf lies outside the predicted concept's subtree. */
        NEGATIVE,
        /** Nothing was verified. */
        UNKNOWN
    }

    /** Validates the components. */
    public ConceptOutcome {
        Objects.requireNonNull(receipt, "receipt must not be null");
        Objects.requireNonNull(verification, "verification must not be null");
        Objects.requireNonNull(verifiedCode, "verifiedCode must not be null");
        Objects.requireNonNull(verifiedPreviousCode, "verifiedPreviousCode must not be null");
        boolean verified = verification == Verification.VERIFIED;
        if (verified != verifiedCode.isPresent() || verified != (observedEvidence != null)) {
            throw new IllegalArgumentException(
                    "verifiedCode and observedEvidence are present exactly for a VERIFIED outcome");
        }
        if (!verified && verifiedPreviousCode.isPresent()) {
            throw new IllegalArgumentException("verifiedPreviousCode needs a VERIFIED outcome");
        }
        if (originCycleOrdinal < 0) {
            throw new IllegalArgumentException("originCycleOrdinal must be non-negative, got: " + originCycleOrdinal);
        }
        Objects.requireNonNull(verifierId, "verifierId must not be null");
        if (verifierId.isBlank() || verifierId.length() > ResonanceMemoryResult.MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("verifierId must be non-blank and at most "
                    + ResonanceMemoryResult.MAX_REFERENCE_LENGTH + " characters");
        }
    }

    /**
     * Creates a verified outcome.
     *
     * @param receipt the prediction
     * @param verifiedCode the observed leaf
     * @param observedEvidence the labeled window's pattern
     * @param verifiedPreviousCode the verified leaf of the preceding window, or empty
     * @param originCycleOrdinal the predicting cycle's ordinal
     * @param verifierId the verifier
     * @return the outcome
     */
    public static ConceptOutcome verified(ConceptPredictionReceipt receipt, long verifiedCode,
                                          SpectralPattern observedEvidence, OptionalLong verifiedPreviousCode,
                                          long originCycleOrdinal, String verifierId) {
        Objects.requireNonNull(observedEvidence, "observedEvidence must not be null");
        return new ConceptOutcome(receipt, Verification.VERIFIED, OptionalLong.of(verifiedCode), observedEvidence,
                verifiedPreviousCode, originCycleOrdinal, verifierId);
    }

    /**
     * Creates an outcome without a verified label.
     *
     * @param receipt the prediction
     * @param verification {@code UNVERIFIED} or {@code CONTRADICTED}
     * @param originCycleOrdinal the predicting cycle's ordinal
     * @param verifierId the verifier
     * @return the outcome
     */
    public static ConceptOutcome unverified(ConceptPredictionReceipt receipt, Verification verification,
                                            long originCycleOrdinal, String verifierId) {
        return new ConceptOutcome(receipt, verification, OptionalLong.empty(), null, OptionalLong.empty(),
                originCycleOrdinal, verifierId);
    }

    /**
     * Returns the polarity against a hierarchy: {@code POSITIVE} when the verified leaf is the predicted concept or in
     * its subtree, {@code NEGATIVE} otherwise, {@code UNKNOWN} without a verified label or when a code is not in the
     * hierarchy.
     *
     * @param hierarchy the hierarchy of the snapshot that predicted
     * @return the polarity
     */
    public Polarity polarity(ConceptHierarchy hierarchy) {
        Objects.requireNonNull(hierarchy, "hierarchy must not be null");
        if (verifiedCode.isEmpty()) {
            return Polarity.UNKNOWN;
        }
        var observed = hierarchy.ordinalOf(verifiedCode.getAsLong());
        var predicted = hierarchy.ordinalOf(receipt.predictedCode());
        if (observed.isEmpty() || predicted.isEmpty()) {
            return Polarity.UNKNOWN;
        }
        return hierarchy.inSubtree(observed.getAsInt(), predicted.getAsInt()) ? Polarity.POSITIVE : Polarity.NEGATIVE;
    }
}
