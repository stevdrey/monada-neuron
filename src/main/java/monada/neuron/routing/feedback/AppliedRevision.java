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
 * duplicate delivery or correction must be compared with. So is a revision that derived nothing, as a marker. Entries
 * compare equal only when everything the contract validates about the claim is equal, apart from the bulky evidence
 * lists.
 *
 * @param identity exact sample revision and definition that was applied
 * @param routingPolicyId routing policy that produced the applied effect (audit provenance)
 * @param routingPolicyVersion version of that routing policy
 * @param supersedesSequence sequence of the earliest revision the sample replaces, as copied from the observation
 * @param catalogVersion catalog snapshot behind the decision
 * @param cutoff ledger cutoff the outcome was evaluated as of
 * @param targetNodeId Node the feedback credited; present exactly when the entry moved a Node
 * @param costPeer peer revision a cost-refined reward depended on; present exactly for {@code REINFORCE_COST_REFINED}
 * @param basis the derived feedback's rule and assessment, or the no-update marker's reason and cohort binding
 * @param disposition what the feedback did
 * @param score applied score; {@code 0.0} exactly for {@code NEUTRAL}
 */
public record AppliedRevision(
        FeedbackIdentity identity,
        String routingPolicyId,
        String routingPolicyVersion,
        Feature<Long> supersedesSequence,
        String catalogVersion,
        long cutoff,
        Optional<UUID> targetNodeId,
        Optional<PeerRevision> costPeer,
        EntryBasis basis,
        FeedbackDisposition disposition,
        double score) {

    /** Validates that the parts agree with each other and the score with the disposition. */
    public AppliedRevision {
        Objects.requireNonNull(identity, "identity must not be null");
        OpaqueTokens.require(routingPolicyId, "routingPolicyId");
        OpaqueTokens.require(routingPolicyVersion, "routingPolicyVersion");
        Objects.requireNonNull(supersedesSequence, "supersedesSequence must not be null");
        OpaqueTokens.require(catalogVersion, "catalogVersion");
        if (cutoff < 0) {
            throw new IllegalArgumentException("cutoff must be non-negative, got: " + cutoff);
        }
        Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");
        Objects.requireNonNull(costPeer, "costPeer must not be null");
        Objects.requireNonNull(basis, "basis must not be null");
        Objects.requireNonNull(disposition, "disposition must not be null");
        if (costPeer.isPresent() != (basis instanceof EntryBasis.Derived derived
                && derived.rule() == ScoreRule.REINFORCE_COST_REFINED)) {
            throw new IllegalArgumentException("a cost peer is recorded exactly for a cost-refined reward");
        }
        if (targetNodeId.isPresent() != (disposition != FeedbackDisposition.NEUTRAL)) {
            throw new IllegalArgumentException("a target Node is recorded exactly when the entry moved a Node");
        }
        if (basis instanceof EntryBasis.NoUpdateMarker && disposition != FeedbackDisposition.NEUTRAL) {
            throw new IllegalArgumentException("only a neutral entry can be a no-update marker");
        }
        if (basis instanceof EntryBasis.Derived derived && derived.rule().disposition() != disposition) {
            throw new IllegalArgumentException(
                    "rule " + derived.rule() + " yields " + derived.rule().disposition() + ", not " + disposition);
        }
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
     * Records that an observation was examined and derived nothing, so an older delivery of the same sample can
     * still be recognized as stale. It is neutral, names no cohort and moves no Node.
     */
    public static AppliedRevision ofNoUpdate(RoutingObservation observation, NoUpdateReason reason) {
        Objects.requireNonNull(observation, "observation must not be null");
        RoutingOutcome outcome = observation.outcome();
        return new AppliedRevision(
                new FeedbackIdentity(observation.experienceRef(), observation.revision(), observation.ledgerSequence(),
                        outcome.decisionRef(), outcome.executedRoute(), outcome.cohortBinding().mappingVersion(),
                        outcome.evaluationPolicyId(), outcome.evaluationPolicyVersion()),
                outcome.routingPolicyId(), outcome.routingPolicyVersion(), observation.supersedesSequence(),
                outcome.catalogVersion(), outcome.cutoff(), Optional.empty(), Optional.empty(),
                new EntryBasis.NoUpdateMarker(reason, outcome.cohortBinding()), FeedbackDisposition.NEUTRAL, 0.0);
    }

    /** Records the effect of a derived feedback. */
    public static AppliedRevision of(RoutingFeedback feedback) {
        Objects.requireNonNull(feedback, "feedback must not be null");
        double score = feedback.entries().stream().mapToDouble(FeedbackEntry::score).sum();
        return new AppliedRevision(feedback.identity(), feedback.routingPolicyId(), feedback.routingPolicyVersion(),
                feedback.supersedesSequence(), feedback.catalogVersion(), feedback.cutoff(),
                feedback.entries().stream().map(FeedbackEntry::targetNodeId).findFirst(),
                // only a bonus depends on the peer; a view that earned nothing is checked on delivery, not kept
                feedback.rule() == ScoreRule.REINFORCE_COST_REFINED ? feedback.costPeer() : Optional.empty(),
                new EntryBasis.Derived(feedback.cohortKey(), feedback.rule(), feedback.derivedAcceptance(),
                        feedback.reasons()),
                feedback.disposition(), score);
    }

    /** Cohort the feedback named; empty for a no-update marker. */
    public Optional<CohortKey> cohortKey() {
        return basis instanceof EntryBasis.Derived derived ? Optional.of(derived.cohortKey()) : Optional.empty();
    }

    /** True when the reward was refined by cost, so a newer revision of its peer makes the bonus obsolete. */
    public boolean costRefined() {
        return basis instanceof EntryBasis.Derived derived && derived.rule() == ScoreRule.REINFORCE_COST_REFINED;
    }

    /** True when this entry is the marker of an observation that was not credited because no Node was bound. */
    public boolean unbound() {
        return basis instanceof EntryBasis.NoUpdateMarker marker && marker.reason() == NoUpdateReason.NO_TARGET_BINDING;
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
