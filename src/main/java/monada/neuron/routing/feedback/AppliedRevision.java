package monada.neuron.routing.feedback;

import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.evolution.FeedbackEntry;

import java.util.Objects;

/**
 * One entry of the caller-owned applied-revision ledger (contract section 7): the effective revision of a sample
 * that the caller has accounted for, with what it did to the state. Neuron keeps no ledger; the caller holds at most
 * {@value RevisionReconciler#MAX_LEDGER} of these per state, one per sample reference.
 *
 * <p>A neutral feedback is recorded too, with score {@code 0.0}: it changed no Node but is the revision that a later
 * duplicate delivery or correction must be compared with.
 *
 * @param identity exact sample revision and definition that was applied
 * @param cohortKey cohort the feedback named
 * @param disposition what the feedback did
 * @param score applied score; {@code 0.0} exactly for {@code NEUTRAL}
 */
public record AppliedRevision(
        FeedbackIdentity identity, CohortKey cohortKey, FeedbackDisposition disposition, double score) {

    /** Validates that the score agrees with the disposition. */
    public AppliedRevision {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(cohortKey, "cohortKey must not be null");
        Objects.requireNonNull(disposition, "disposition must not be null");
        boolean consistent = switch (disposition) {
            case REINFORCE -> score > 0.0 && score <= 1.0;
            case PENALIZE -> score < 0.0 && score >= -1.0;
            case NEUTRAL -> score == 0.0;
        };
        if (!consistent) {
            throw new IllegalArgumentException(disposition + " cannot have applied score " + score);
        }
    }

    /** Records the effect of a derived feedback. */
    public static AppliedRevision of(RoutingFeedback feedback) {
        Objects.requireNonNull(feedback, "feedback must not be null");
        double score = feedback.entries().stream().mapToDouble(FeedbackEntry::score).sum();
        return new AppliedRevision(feedback.identity(), feedback.cohortKey(), feedback.disposition(), score);
    }

    /** Sample reference. */
    public String experienceRef() {
        return identity.experienceRef();
    }

    /** Sample revision. */
    public long revision() {
        return identity.revision();
    }

    /** Ledger sequence of the applied revision. */
    public long ledgerSequence() {
        return identity.ledgerSequence();
    }

    /** True when applying it moved a Node, so superseding it cannot be done by subtraction. */
    public boolean changedState() {
        return disposition != FeedbackDisposition.NEUTRAL;
    }
}
