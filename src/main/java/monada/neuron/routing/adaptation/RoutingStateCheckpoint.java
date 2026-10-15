package monada.neuron.routing.adaptation;

import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.CohortKey;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable checkpoint of one {@link RoutingStateStore} at its {@code processedCutoff} (contract section 7): every
 * cohort's Node channels and support, active and dormant, and the digest of every ledger entry it covers. It is
 * caller-owned and opt-in; Neuron keeps no registry of checkpoints and never persists one.
 *
 * <p>A checkpoint lets the caller {@linkplain RoutingStateStore#compact compact} the ledger entries it covers, so a
 * full ledger no longer blocks learning, and {@linkplain RoutingStateStore#rebuildFrom rebuild} from it, replaying only
 * later observations. It is valid for a rebuild only while no revision at or below its {@code processedCutoff} is
 * superseded: a usable checkpoint's cutoff is <b>strictly less than</b> the {@code supersedesSequence} of the earliest
 * superseded revision. Starting from a valid checkpoint gives a state identical to a rebuild from scratch, digest for
 * digest.
 *
 * <p>Retryable markers ({@code NO_TARGET_BINDING}, {@code PROVENANCE_MISMATCH}) are carried as entries, not only in
 * the digest: their redelivery can still become a first application, so a compacted store must still recognize them.
 * Footprint: {@code O(C + M)} for {@code C <= 256} cohorts and {@code M} retained markers.
 *
 * @param scopeId scope of the partition
 * @param featureSchemaVersion feature schema of the definition
 * @param evaluationPolicyId evaluation policy id of the partition
 * @param evaluationPolicyVersion evaluation policy version of the partition
 * @param mappingVersion composite {@code mappingVersion} of the definition
 * @param policyId routing policy the checkpoint was taken for (provenance only)
 * @param policyVersion version of that routing policy
 * @param processedCutoff the store's watermark when the checkpoint was taken; every covered entry lies at or below it
 * @param preferenceSnapshot every cohort in canonical {@link CohortKey} order, at most {@value RoutingStateStore#MAX_COHORTS}
 * @param ledgerDigest digest of every ledger entry the state had accounted for, compacted or not
 * @param retainedMarkers the retryable markers among those entries, in sample-reference code-point order
 */
public record RoutingStateCheckpoint(
        String scopeId,
        String featureSchemaVersion,
        String evaluationPolicyId,
        String evaluationPolicyVersion,
        String mappingVersion,
        String policyId,
        String policyVersion,
        long processedCutoff,
        List<CheckpointCohort> preferenceSnapshot,
        LedgerDigest ledgerDigest,
        List<AppliedRevision> retainedMarkers) {

    /** Validates the identity, bounds, canonical order and that every marker is a covered retryable marker. */
    public RoutingStateCheckpoint {
        OpaqueTokens.require(scopeId, "scopeId");
        OpaqueTokens.require(featureSchemaVersion, "featureSchemaVersion");
        OpaqueTokens.require(evaluationPolicyId, "evaluationPolicyId");
        OpaqueTokens.require(evaluationPolicyVersion, "evaluationPolicyVersion");
        OpaqueTokens.require(mappingVersion, "mappingVersion");
        OpaqueTokens.require(policyId, "policyId");
        OpaqueTokens.require(policyVersion, "policyVersion");
        if (processedCutoff < 0) {
            throw new IllegalArgumentException("processedCutoff must be non-negative, got: " + processedCutoff);
        }
        Objects.requireNonNull(preferenceSnapshot, "preferenceSnapshot must not be null");
        if (preferenceSnapshot.size() > RoutingStateStore.MAX_COHORTS) {
            throw new IllegalArgumentException("a checkpoint allows at most " + RoutingStateStore.MAX_COHORTS
                    + " cohorts, got: " + preferenceSnapshot.size());
        }
        var cohorts = new ArrayList<CheckpointCohort>(preferenceSnapshot.size());
        for (CheckpointCohort cohort : preferenceSnapshot) {
            Objects.requireNonNull(cohort, "preferenceSnapshot must not contain null");
            if (!cohort.key().scopeId().equals(scopeId)) {
                throw new IllegalArgumentException("cohort " + cohort.key() + " does not belong to scope " + scopeId);
            }
            cohorts.add(cohort);
        }
        cohorts.sort(Comparator.comparing(CheckpointCohort::key));
        for (int i = 1; i < cohorts.size(); i++) {
            if (cohorts.get(i - 1).key().equals(cohorts.get(i).key())) {
                throw new IllegalArgumentException("cohort " + cohorts.get(i).key() + " appears twice");
            }
        }
        preferenceSnapshot = List.copyOf(cohorts);
        Objects.requireNonNull(ledgerDigest, "ledgerDigest must not be null");
        Objects.requireNonNull(retainedMarkers, "retainedMarkers must not be null");
        var markers = new ArrayList<AppliedRevision>(retainedMarkers.size());
        for (AppliedRevision marker : retainedMarkers) {
            Objects.requireNonNull(marker, "retainedMarkers must not contain null");
            if (!marker.retryable()) {
                throw new IllegalArgumentException("only retryable markers are retained, got: " + marker.basis());
            }
            if (marker.ledgerSequence() > processedCutoff) {
                throw new IllegalArgumentException("marker " + marker.experienceRef() + " at sequence "
                        + marker.ledgerSequence() + " lies beyond the checkpoint's cutoff " + processedCutoff);
            }
            markers.add(marker);
        }
        markers.sort(Comparator.comparing(AppliedRevision::experienceRef, OpaqueTokens.CODE_POINT_ORDER));
        for (int i = 1; i < markers.size(); i++) {
            if (markers.get(i - 1).experienceRef().equals(markers.get(i).experienceRef())) {
                throw new IllegalArgumentException("marker " + markers.get(i).experienceRef() + " appears twice");
            }
        }
        if (markers.size() > ledgerDigest.entries()) {
            throw new IllegalArgumentException("the digest covers " + ledgerDigest.entries() + " entries, fewer than "
                    + markers.size() + " retained markers");
        }
        retainedMarkers = List.copyOf(markers);
    }
}
