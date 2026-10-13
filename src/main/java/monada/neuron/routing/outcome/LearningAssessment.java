package monada.neuron.routing.outcome;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Result of {@link OutcomeEvaluator#assess}. It states whether the observation can support routing feedback and why;
 * it is not a feedback artifact and carries no score.
 *
 * @param derivedAcceptance acceptance derived as the Store defines it
 * @param verdict whether the observation supports a positive or a negative example
 * @param reasons ineligibility reasons, canonicalized to declaration order, unique; may accompany a candidate verdict (for example a
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
        reasons = Ordering.canonical(reasons, Comparator.naturalOrder(), IneligibilityReason.values().length, "reasons");
        mismatches = Ordering.canonical(mismatches, Comparator.naturalOrder(), ProvenanceField.values().length,
                "mismatches");
        effectiveEvidence = Ordering.canonical(effectiveEvidence, Comparator.comparing(EffectiveEvidence::dimension),
                QualityDimension.values().length, "effectiveEvidence");
        provenFailures = List.copyOf(provenFailures);
        if (excludedObservations < 0) {
            throw new IllegalArgumentException("excludedObservations must be non-negative");
        }
        if (reasons.contains(IneligibilityReason.PROVENANCE_MISMATCH) != !mismatches.isEmpty()) {
            throw new IllegalArgumentException("mismatches must be listed exactly when PROVENANCE_MISMATCH is");
        }
        List<QualityDimension> failed = effectiveEvidence.stream()
                .filter(e -> e.state() == QualityState.FAIL).map(EffectiveEvidence::dimension).toList();
        if (!failed.equals(provenFailures)) {
            throw new IllegalArgumentException(
                    "provenFailures " + provenFailures + " must equal the effective FAIL dimensions " + failed);
        }
        if (verdict == EligibilityVerdict.NEGATIVE_CANDIDATE && derivedAcceptance != DerivedAcceptance.NOT_ACCEPTED
                && derivedAcceptance != DerivedAcceptance.ACCEPTED_UNVALIDATED) {
            throw new IllegalArgumentException("a negative candidate cannot be " + derivedAcceptance);
        }
        if (verdict == EligibilityVerdict.POSITIVE_CANDIDATE
                && (derivedAcceptance != DerivedAcceptance.VALIDATED_ACCEPTED || !provenFailures.isEmpty()
                        || !reasons.isEmpty())) {
            throw new IllegalArgumentException("a positive candidate must be validated, failure-free and reason-free");
        }
        if (verdict == EligibilityVerdict.POSITIVE_CANDIDATE && (effectiveEvidence.isEmpty()
                || effectiveEvidence.stream().anyMatch(e -> e.state() != QualityState.PASS
                        && e.state() != QualityState.NOT_APPLICABLE))) {
            throw new IllegalArgumentException(
                    "a positive candidate needs effective evidence and every effective state PASS or NOT_APPLICABLE");
        }
        if (verdict == EligibilityVerdict.NEGATIVE_CANDIDATE && provenFailures.isEmpty()) {
            throw new IllegalArgumentException("a negative candidate needs a proven mandatory failure");
        }
        if (verdict == EligibilityVerdict.NEGATIVE_CANDIDATE && reasons.stream().anyMatch(IneligibilityReason::blocking)) {
            throw new IllegalArgumentException("a negative candidate must not carry a blocking reason: " + reasons);
        }
    }
}
