package monada.neuron.reasoning.concept;

import monada.neuron.memory.ResonanceMemoryResult;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Bounds of {@link ConceptFeedbackPolicy} (ADR 0049): the outcomes one update may absorb, the oldest predicting cycle
 * an outcome may come from, the feedback a leaf may absorb relative to its training windows, and the verifiers whose
 * outcomes are trusted.
 *
 * @param maxOutcomesPerUpdate largest batch, in {@code [1, MAX_OUTCOMES_PER_UPDATE]}
 * @param maxEvidenceAge largest {@code currentCycleOrdinal - originCycleOrdinal}, at least {@code 0}
 * @param maxFeedbackShare feedback windows a leaf may absorb per training window, finite in {@code (0, 64]}
 * @param trustedVerifiers identities of the trusted host verifiers, 1 to {@code MAX_VERIFIERS}, stored sorted and
 *     unique
 */
public record ConceptFeedbackLimits(int maxOutcomesPerUpdate, long maxEvidenceAge, double maxFeedbackShare,
                                    List<String> trustedVerifiers) {

    /** Largest batch of one update. */
    public static final int MAX_OUTCOMES_PER_UPDATE = 4096;

    /** Largest number of trusted verifiers. */
    public static final int MAX_VERIFIERS = 16;

    /** Default batch bound. */
    public static final int DEFAULT_MAX_OUTCOMES = 64;

    /** Default evidence age, in caller cycles. */
    public static final long DEFAULT_MAX_EVIDENCE_AGE = 64;

    /** Default feedback share: feedback may at most double a leaf's windows. */
    public static final double DEFAULT_MAX_FEEDBACK_SHARE = 1.0;

    /** Validates the bounds and canonicalizes the verifiers. */
    public ConceptFeedbackLimits {
        if (maxOutcomesPerUpdate < 1 || maxOutcomesPerUpdate > MAX_OUTCOMES_PER_UPDATE) {
            throw new IllegalArgumentException("maxOutcomesPerUpdate must be in [1, " + MAX_OUTCOMES_PER_UPDATE
                    + "], got: " + maxOutcomesPerUpdate);
        }
        if (maxEvidenceAge < 0) {
            throw new IllegalArgumentException("maxEvidenceAge must be non-negative, got: " + maxEvidenceAge);
        }
        if (!(maxFeedbackShare > 0.0 && maxFeedbackShare <= 64.0)) {
            throw new IllegalArgumentException("maxFeedbackShare must be in (0, 64], got: " + maxFeedbackShare);
        }
        Objects.requireNonNull(trustedVerifiers, "trustedVerifiers must not be null");
        TreeSet<String> verifiers = new TreeSet<>();
        for (String verifier : trustedVerifiers) {
            Objects.requireNonNull(verifier, "trustedVerifiers must not contain null");
            if (verifier.isBlank() || verifier.length() > ResonanceMemoryResult.MAX_REFERENCE_LENGTH) {
                throw new IllegalArgumentException("a verifier must be non-blank and at most "
                        + ResonanceMemoryResult.MAX_REFERENCE_LENGTH + " characters");
            }
            verifiers.add(verifier);
        }
        if (verifiers.isEmpty() || verifiers.size() > MAX_VERIFIERS) {
            throw new IllegalArgumentException("1.." + MAX_VERIFIERS + " trusted verifiers, got: " + verifiers.size());
        }
        trustedVerifiers = List.copyOf(verifiers);
    }

    /**
     * Returns the default bounds trusting the given verifiers.
     *
     * @param trustedVerifiers the trusted verifier identities
     * @return the limits
     */
    public static ConceptFeedbackLimits trusting(String... trustedVerifiers) {
        return new ConceptFeedbackLimits(DEFAULT_MAX_OUTCOMES, DEFAULT_MAX_EVIDENCE_AGE, DEFAULT_MAX_FEEDBACK_SHARE,
                List.of(trustedVerifiers));
    }

    /** Returns whether a verifier is trusted. O(log v). */
    public boolean trusts(String verifierId) {
        return Collections.binarySearch(trustedVerifiers, verifierId) >= 0;
    }
}
