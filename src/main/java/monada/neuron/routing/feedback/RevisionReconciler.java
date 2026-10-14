package monada.neuron.routing.feedback;

import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.RoutingObservation;
import monada.neuron.routing.outcome.RoutingOutcome;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decides how a derivation relates to the caller's applied-revision ledger, so each effective revision is applied
 * exactly once, a superseded reward or penalty is detected and an incremental application never differs from a rebuild
 * (contract section 7).
 *
 * <p>The ledger holds one entry per sample reference: its latest examined revision. Rules, in the order they are
 * checked:
 * <ul>
 * <li>a no-update that is not recorded (another state partition, or a wrong pairing): {@code NotRecorded};</li>
 * <li>a newer revision at a ledger position that is not strictly later (or an older one that is not strictly
 *     earlier) than the entry's is inconsistent caller data and rejected;</li>
 * <li>no entry: {@code CapacityExhausted} when {@value #MAX_LEDGER} entries exist, else {@code Apply} for a derived
 *     feedback or {@code RecordOnly} (a marker so an older delivery stays stale) when nothing was derived;</li>
 * <li>equal revision: an identical entry (identity, routing policy, ancestry, catalog, cutoff, rule, assessment, Node,
 *     peer; a marker equals a marker of the same reason and binding) is a duplicate delivery
 *     ({@code AlreadyApplied}); a marker for a missing Node binding followed by a derived feedback is a first
 *     application; anything else is {@code ConflictingRevision};</li>
 * <li>older revision: {@code StaleRevision};</li>
 * <li>newer revision: {@code Apply} or {@code RecordOnly} replacing a neutral entry, but {@code RebuildRequired} when
 *     the entry moved a Node, whatever the new revision says, including when it derives no feedback at all;</li>
 * <li>an application that moves a Node is incremental only if its applied position {@code (ledgerSequence,
 *     experienceRef by code point)} is after every entry that moved a Node, because adaptation is clamped and order
 *     dependent; otherwise {@code RebuildRequired} from its own position;</li>
 * <li>a derived reward that looked at a revision of its cost peer that the ledger already superseded, or the same
 *     revision as an entry that differs from it only in the cost refinement: {@code StalePeerDependency}, nothing
 *     applied, the caller derives again against the latest peer;</li>
 * <li>an {@code Apply}, {@code RecordOnly} or {@code RebuildRequired} becomes {@code RebuildRequired} (or starts
 *     earlier) when an entry was cost-refined against an older revision of this very sample.</li>
 * </ul>
 *
 * <p>Stateless and side-effect free; {@code O(L)} for the ledger of at most {@value #MAX_LEDGER} entries, which is a
 * constant number of scans per delivered feedback, so no index is built.
 */
public final class RevisionReconciler {

    /** Maximum ledger entries per state (contract section 4); equal to the rebuild bound. */
    public static final int MAX_LEDGER = 4096;

    /**
     * Relates one derivation to the ledger.
     *
     * @param ledger the caller's ledger: at most {@value #MAX_LEDGER} entries, unique sample references
     * @param observation the observation the derivation answers
     * @param derivation what the policy derived for it
     * @return what the caller should do; the ledger is not modified
     * @throws IllegalArgumentException if the ledger is oversized or repeats a reference, the derived feedback does
     *         not answer the observation, or the observation's position is inconsistent with its ledger entry
     */
    public Reconciliation reconcile(
            List<AppliedRevision> ledger, RoutingObservation observation, FeedbackDerivation derivation) {
        Objects.requireNonNull(ledger, "ledger must not be null");
        Objects.requireNonNull(observation, "observation must not be null");
        Objects.requireNonNull(derivation, "derivation must not be null");
        requireLedger(ledger);
        Reconciliation result = classify(ledger, observation, derivation);
        if (result instanceof Reconciliation.Apply && derivation instanceof FeedbackDerivation.Derived derived
                && derived.feedback().costPeer().isPresent()) {
            PeerRevision peer = derived.feedback().costPeer().get();
            for (AppliedRevision entry : ledger) {
                if (entry.experienceRef().equals(peer.experienceRef()) && entry.revision() > peer.revision()) {
                    return new Reconciliation.StalePeerDependency(entry);
                }
            }
        }
        if (!(result instanceof Reconciliation.Apply) && !(result instanceof Reconciliation.RecordOnly)
                && !(result instanceof Reconciliation.RebuildRequired)) {
            return result;
        }
        // a reward that was refined by a cost peer is obsolete once that peer has a newer revision; the earliest such
        // reward also bounds which checkpoints can still be trusted
        AppliedRevision dependent = null;
        for (AppliedRevision entry : ledger) {
            if (entry.costRefined()
                    && entry.costPeer().get().experienceRef().equals(observation.experienceRef())
                    && entry.costPeer().get().revision() < observation.revision()
                    && (dependent == null || entry.ledgerSequence() < dependent.ledgerSequence())) {
                dependent = entry;
            }
        }
        if (dependent == null) {
            return result;
        }
        if (result instanceof Reconciliation.RebuildRequired rebuild) {
            return dependent.ledgerSequence() < rebuild.affectedFromSequence()
                    ? new Reconciliation.RebuildRequired(rebuild.superseded(), dependent.ledgerSequence())
                    : rebuild;
        }
        return new Reconciliation.RebuildRequired(dependent, dependent.ledgerSequence());
    }

    private Reconciliation classify(
            List<AppliedRevision> ledger, RoutingObservation observation, FeedbackDerivation derivation) {
        if (derivation instanceof FeedbackDerivation.NoUpdate none && !none.reason().recordable()) {
            return new Reconciliation.NotRecorded(none.reason());
        }
        AppliedRevision incoming;
        if (derivation instanceof FeedbackDerivation.Derived derived) {
            RoutingFeedback feedback = derived.feedback();
            FeedbackIdentity identity = feedback.identity();
            requireAnswers(feedback, observation);
            incoming = AppliedRevision.of(feedback);
            if (!identity.experienceRef().equals(observation.experienceRef())
                    || identity.revision() != observation.revision()
                    || identity.ledgerSequence() != observation.ledgerSequence()
                    || !feedback.supersedesSequence().equals(observation.supersedesSequence())) {
                throw new IllegalArgumentException("the feedback answers " + identity.experienceRef() + "@"
                        + identity.revision() + " at sequence " + identity.ledgerSequence()
                        + ", not the observation " + observation.experienceRef() + "@" + observation.revision()
                        + " at sequence " + observation.ledgerSequence());
            }
        } else {
            incoming = AppliedRevision.ofNoUpdate(observation, ((FeedbackDerivation.NoUpdate) derivation).reason());
        }
        boolean derived = derivation instanceof FeedbackDerivation.Derived;
        AppliedRevision existing = null;
        for (AppliedRevision entry : ledger) {
            if (entry.experienceRef().equals(observation.experienceRef())) {
                existing = entry;
                break;
            }
        }
        if (existing == null) {
            if (ledger.size() >= MAX_LEDGER) {
                return new Reconciliation.CapacityExhausted();
            }
            return application(ledger, incoming, derived, Optional.empty());
        }
        requireConsistentPositions(existing, observation);
        if (observation.revision() < existing.revision()) {
            return new Reconciliation.StaleRevision(existing);
        }
        if (observation.revision() == existing.revision()) {
            if (incoming.equals(existing)) {
                return new Reconciliation.AlreadyApplied(existing);
            }
            // the Node was not bound when this revision was first seen; deriving it now is its first application
            if (existing.unbound() && derived && sameClaim(existing, incoming)) {
                return application(ledger, incoming, true, Optional.of(existing));
            }
            if (differsOnlyInCost(existing, incoming)) {
                return new Reconciliation.StalePeerDependency(existing);
            }
            return new Reconciliation.ConflictingRevision(existing);
        }
        if (existing.changedState()) {
            return new Reconciliation.RebuildRequired(existing, affectedFrom(existing, observation));
        }
        return application(ledger, incoming, derived, Optional.of(existing));
    }

    /**
     * Applies or records the incoming entry, unless it moves a Node out of applied order: adaptation is clamped and
     * therefore order dependent, so only a rebuild from the incoming position equals a build from scratch.
     */
    private Reconciliation application(
            List<AppliedRevision> ledger, AppliedRevision incoming, boolean derived, Optional<AppliedRevision> replaces) {
        if (incoming.changedState()) {
            AppliedRevision later = null;
            for (AppliedRevision entry : ledger) {
                if (entry.changedState() && !entry.experienceRef().equals(incoming.experienceRef())
                        && compareApplied(entry, incoming) > 0
                        && (later == null || compareApplied(entry, later) < 0)) {
                    later = entry;
                }
            }
            if (later != null) {
                return new Reconciliation.RebuildRequired(later, incoming.ledgerSequence());
            }
        }
        return derived
                ? new Reconciliation.Apply(replaces)
                : new Reconciliation.RecordOnly(incoming, replaces);
    }

    /** Applied order of two entries: ledger sequence, then sample reference by code point. */
    private int compareApplied(AppliedRevision left, AppliedRevision right) {
        int bySequence = Long.compare(left.ledgerSequence(), right.ledgerSequence());
        return bySequence != 0 ? bySequence
                : OpaqueTokens.compareCodePoints(left.experienceRef(), right.experienceRef());
    }

    /**
     * True when two derived rewards of one sample revision agree on everything but the cost refinement (rule, score
     * and peer): the difference is the view of the peer the derivation was made against, not a different claim.
     */
    private boolean differsOnlyInCost(AppliedRevision existing, AppliedRevision incoming) {
        return existing.basis() instanceof EntryBasis.Derived left && incoming.basis() instanceof EntryBasis.Derived right
                && existing.disposition() == FeedbackDisposition.REINFORCE
                && incoming.disposition() == FeedbackDisposition.REINFORCE
                && existing.identity().equals(incoming.identity())
                && existing.routingPolicyId().equals(incoming.routingPolicyId())
                && existing.routingPolicyVersion().equals(incoming.routingPolicyVersion())
                && existing.supersedesSequence().equals(incoming.supersedesSequence())
                && existing.catalogVersion().equals(incoming.catalogVersion())
                && existing.cutoff() == incoming.cutoff()
                && existing.targetNodeId().equals(incoming.targetNodeId())
                && left.cohortKey().equals(right.cohortKey())
                && left.acceptance() == right.acceptance()
                && left.reasons().equals(right.reasons())
                // a different score alone is another scoring configuration, not another view of the peer
                && (left.rule() != right.rule() || !existing.costPeer().equals(incoming.costPeer()));
    }

    /** True when an unbound marker and a derived entry are the same claim apart from the binding outcome. */
    private boolean sameClaim(AppliedRevision marker, AppliedRevision derived) {
        return marker.identity().equals(derived.identity())
                && marker.routingPolicyId().equals(derived.routingPolicyId())
                && marker.routingPolicyVersion().equals(derived.routingPolicyVersion())
                && marker.supersedesSequence().equals(derived.supersedesSequence())
                && marker.catalogVersion().equals(derived.catalogVersion())
                && marker.cutoff() == derived.cutoff();
    }

    /** A newer revision sits at a strictly later ledger position and an older one at a strictly earlier one. */
    private void requireConsistentPositions(AppliedRevision existing, RoutingObservation observation) {
        boolean newer = observation.revision() > existing.revision();
        boolean older = observation.revision() < existing.revision();
        if ((newer && observation.ledgerSequence() <= existing.ledgerSequence())
                || (older && observation.ledgerSequence() >= existing.ledgerSequence())) {
            throw new IllegalArgumentException("revision " + observation.revision() + " of "
                    + observation.experienceRef() + " at sequence " + observation.ledgerSequence()
                    + " is inconsistent with the applied revision " + existing.revision() + " at sequence "
                    + existing.ledgerSequence());
        }
    }

    /** Requires the feedback to answer this very observation, not merely one with the same position. */
    private void requireAnswers(RoutingFeedback feedback, RoutingObservation observation) {
        RoutingOutcome outcome = observation.outcome();
        FeedbackIdentity identity = feedback.identity();
        CohortKey key = feedback.cohortKey();
        boolean answers = identity.decisionRef().equals(outcome.decisionRef())
                && identity.route().equals(outcome.executedRoute())
                && identity.mappingVersion().equals(outcome.cohortBinding().mappingVersion())
                && identity.evaluationPolicyId().equals(outcome.evaluationPolicyId())
                && identity.evaluationPolicyVersion().equals(outcome.evaluationPolicyVersion())
                && feedback.routingPolicyId().equals(outcome.routingPolicyId())
                && feedback.routingPolicyVersion().equals(outcome.routingPolicyVersion())
                && feedback.catalogVersion().equals(outcome.catalogVersion())
                && feedback.cutoff() == outcome.cutoff()
                && key.scopeId().equals(outcome.decisionRef().scopeId())
                && key.route().equals(outcome.executedRoute())
                && key.cohortBucket().equals(outcome.cohortBinding().cohortBucket());
        if (!answers) {
            throw new IllegalArgumentException("the feedback for " + identity.experienceRef() + "@"
                    + identity.revision() + " was not derived from this observation's outcome");
        }
    }

    /** The earliest sequence whose effect may be obsolete: the replaced revision's own, or earlier if declared. */
    private long affectedFrom(AppliedRevision existing, RoutingObservation observation) {
        return observation.supersedesSequence() instanceof Feature.Known<Long> known
                ? Math.min(known.value(), existing.ledgerSequence())
                : existing.ledgerSequence();
    }

    private void requireLedger(List<AppliedRevision> ledger) {
        if (ledger.size() > MAX_LEDGER) {
            throw new IllegalArgumentException(
                    "ledger allows at most " + MAX_LEDGER + " entries, got: " + ledger.size());
        }
        Set<String> references = new HashSet<>(ledger.size() * 2);
        for (AppliedRevision entry : ledger) {
            Objects.requireNonNull(entry, "ledger must not be null");
            if (!references.add(entry.experienceRef())) {
                throw new IllegalArgumentException("ledger holds two entries for " + entry.experienceRef());
            }
        }
    }
}
