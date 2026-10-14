package monada.neuron.routing.feedback;

import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.evolution.FeedbackEntry;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One entry of the caller-owned applied-revision ledger (contract section 7): the effective revision of a sample
 * that the caller has accounted for, with what it did to the state. Neuron keeps no ledger; the caller holds at most
 * {@value RevisionReconciler#MAX_LEDGER} of these per state, one per sample reference.
 *
 * <p>A neutral feedback is recorded too, with score {@code 0.0}: it changed no Node but is the revision that a later
 * duplicate delivery or correction must be compared with.
 *
 * @param identity exact sample revision and definition that was applied
 * @param routingPolicyId routing policy that produced the applied effect (audit provenance)
 * @param routingPolicyVersion version of that routing policy
 * @param supersedesSequence sequence of the earliest revision the sample replaces, as copied from the observation
 * @param cohortKey cohort the feedback named; empty for a no-update marker
 * @param targetNodeId Node the feedback credited; present exactly when the entry moved a Node
 * @param disposition what the feedback did
 * @param score applied score; {@code 0.0} exactly for {@code NEUTRAL}
 */
public record AppliedRevision(
        FeedbackIdentity identity,
        String routingPolicyId,
        String routingPolicyVersion,
        Feature<Long> supersedesSequence,
        Optional<CohortKey> cohortKey,
        Optional<UUID> targetNodeId,
        FeedbackDisposition disposition,
        double score) {

    /** Validates that the score agrees with the disposition. */
    public AppliedRevision {
        Objects.requireNonNull(identity, "identity must not be null");
        OpaqueTokens.require(routingPolicyId, "routingPolicyId");
        OpaqueTokens.require(routingPolicyVersion, "routingPolicyVersion");
        Objects.requireNonNull(supersedesSequence, "supersedesSequence must not be null");
        Objects.requireNonNull(cohortKey, "cohortKey must not be null");
        Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");
        if (targetNodeId.isPresent() != (disposition != FeedbackDisposition.NEUTRAL)) {
            throw new IllegalArgumentException("a target Node is recorded exactly when the entry moved a Node");
        }
        if (cohortKey.isEmpty() && disposition != FeedbackDisposition.NEUTRAL) {
            throw new IllegalArgumentException("only a neutral entry can omit its cohort");
        }
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

    /**
     * Records that an observation was examined and derived no feedback, so an older delivery of the same sample can
     * still be recognized as stale. It is neutral, names no cohort and moves no Node.
     */
    public static AppliedRevision ofNoUpdate(RoutingObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        RoutingOutcome outcome = observation.outcome();
        return new AppliedRevision(
                new FeedbackIdentity(observation.experienceRef(), observation.revision(), observation.ledgerSequence(),
                        outcome.decisionRef(), outcome.executedRoute(), outcome.cohortBinding().mappingVersion(),
                        outcome.evaluationPolicyId(), outcome.evaluationPolicyVersion()),
                outcome.routingPolicyId(), outcome.routingPolicyVersion(), observation.supersedesSequence(),
                Optional.empty(), Optional.empty(), FeedbackDisposition.NEUTRAL, 0.0);
    }

    /** Records the effect of a derived feedback. */
    public static AppliedRevision of(RoutingFeedback feedback) {
        Objects.requireNonNull(feedback, "feedback must not be null");
        double score = feedback.entries().stream().mapToDouble(FeedbackEntry::score).sum();
        return new AppliedRevision(feedback.identity(), feedback.routingPolicyId(), feedback.routingPolicyVersion(),
                feedback.supersedesSequence(), Optional.of(feedback.cohortKey()),
                feedback.entries().stream().map(FeedbackEntry::targetNodeId).findFirst(), feedback.disposition(), score);
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
