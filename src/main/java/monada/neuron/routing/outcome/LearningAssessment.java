package monada.neuron.routing.outcome;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link OutcomeEvaluator#assess}. It states whether the observation can support routing feedback and why;
 * it is not a feedback artifact and carries no score.
 *
 * @param derivedAcceptance acceptance derived as the Store defines it
 * @param verdict whether the observation supports a positive or a negative example
 * @param reasons ineligibility reasons in declaration order, unique; may accompany a candidate verdict (for example a
 *         missing non-failing dimension next to a proven failure)
 * @param mismatches provenance fields that disagree, in declaration order; non-empty iff {@code PROVENANCE_MISMATCH}
 * @param effectiveEvidence effective state per mandatory dimension, in dimension order; a dimension without an
 *         applicable observation is absent, never defaulted
 * @param provenFailures mandatory dimensions whose effective state is {@code FAIL}, in dimension order
 * @param excludedObservations observations ignored: after the cutoff, superseded revisions, another attempt or
 *         another evaluation policy
 */
public record LearningAssessment(
        DerivedAcceptance derivedAcceptance,
        EligibilityVerdict verdict,
        List<IneligibilityReason> reasons,
        List<ProvenanceField> mismatches,
        List<EffectiveEvidence> effectiveEvidence,
        List<QualityDimension> provenFailures,
        int excludedObservations) {

    /** The effective judgment of one mandatory dimension. */
    public record EffectiveEvidence(QualityDimension dimension, QualityState state, long ledgerSequence) {

        /** Requires both parts. */
        public EffectiveEvidence {
            Objects.requireNonNull(dimension, "dimension must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    /** Checks the verdict against the reasons and copies the lists. */
    public LearningAssessment {
        Objects.requireNonNull(derivedAcceptance, "derivedAcceptance must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        reasons = List.copyOf(reasons);
        mismatches = List.copyOf(mismatches);
        effectiveEvidence = List.copyOf(effectiveEvidence);
        provenFailures = List.copyOf(provenFailures);
        if (excludedObservations < 0) {
            throw new IllegalArgumentException("excludedObservations must be non-negative");
        }
        for (int i = 1; i < reasons.size(); i++) {
            if (reasons.get(i - 1).compareTo(reasons.get(i)) >= 0) {
                throw new IllegalArgumentException("reasons must be unique and in declaration order");
            }
        }
        if (reasons.contains(IneligibilityReason.PROVENANCE_MISMATCH) != !mismatches.isEmpty()) {
            throw new IllegalArgumentException("mismatches must be listed exactly when PROVENANCE_MISMATCH is");
        }
        if (verdict == EligibilityVerdict.POSITIVE_CANDIDATE
                && (derivedAcceptance != DerivedAcceptance.VALIDATED_ACCEPTED || !provenFailures.isEmpty()
                        || !reasons.isEmpty())) {
            throw new IllegalArgumentException("a positive candidate must be validated, failure-free and reason-free");
        }
        if (verdict == EligibilityVerdict.NEGATIVE_CANDIDATE && provenFailures.isEmpty()) {
            throw new IllegalArgumentException("a negative candidate needs a proven mandatory failure");
        }
    }
}
