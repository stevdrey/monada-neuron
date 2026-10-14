package monada.neuron.routing.feedback;

import monada.neuron.routing.features.Feature;
import monada.neuron.routing.outcome.RoutingObservation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decides how a derivation relates to the caller's applied-revision ledger, so each effective revision is applied
 * exactly once and a superseded reward or penalty is detected (contract section 7).
 *
 * <p>The ledger holds one entry per sample reference: its latest applied revision. Rules, by comparing the
 * observation's revision with the entry of the same reference:
 * <ul>
 * <li>no entry: {@code Apply}, or {@code CapacityExhausted} when {@value #MAX_LEDGER} entries exist; a derivation
 *     with no feedback changes nothing;</li>
 * <li>equal revision: identical identity and score is a duplicate delivery ({@code AlreadyApplied}); anything else is
 *     {@code ConflictingRevision};</li>
 * <li>older revision: {@code StaleRevision};</li>
 * <li>newer revision: {@code Apply} replacing a neutral entry, but {@code RebuildRequired} when the entry moved a
 *     Node, whatever the new revision says, including when it derives no feedback at all.</li>
 * </ul>
 *
 * <p>Stateless and side-effect free; {@code O(L)} for the ledger of at most {@value #MAX_LEDGER} entries, which is a
 * single scan per delivered feedback, so no index is built.
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
     * @throws IllegalArgumentException if the ledger is oversized or repeats a reference, or the derived feedback
     *         does not answer the observation
     */
    public Reconciliation reconcile(
            List<AppliedRevision> ledger, RoutingObservation observation, FeedbackDerivation derivation) {
        Objects.requireNonNull(ledger, "ledger must not be null");
        Objects.requireNonNull(observation, "observation must not be null");
        Objects.requireNonNull(derivation, "derivation must not be null");
        requireLedger(ledger);
        RoutingFeedback incoming = null;
        if (derivation instanceof FeedbackDerivation.Derived derived) {
            incoming = derived.feedback();
            FeedbackIdentity identity = incoming.identity();
            if (!identity.experienceRef().equals(observation.experienceRef())
                    || identity.revision() != observation.revision()
                    || identity.ledgerSequence() != observation.ledgerSequence()
                    || !incoming.supersedesSequence().equals(observation.supersedesSequence())) {
                throw new IllegalArgumentException("the feedback answers " + identity.experienceRef() + "@"
                        + identity.revision() + " at sequence " + identity.ledgerSequence()
                        + ", not the observation " + observation.experienceRef() + "@" + observation.revision()
                        + " at sequence " + observation.ledgerSequence());
            }
        }
        AppliedRevision existing = null;
        for (AppliedRevision entry : ledger) {
            if (entry.experienceRef().equals(observation.experienceRef())) {
                existing = entry;
                break;
            }
        }
        if (existing == null) {
            if (incoming == null) {
                return new Reconciliation.NoChange();
            }
            return ledger.size() >= MAX_LEDGER
                    ? new Reconciliation.CapacityExhausted()
                    : new Reconciliation.Apply(Optional.empty());
        }
        if (observation.revision() < existing.revision()) {
            return new Reconciliation.StaleRevision(existing);
        }
        if (observation.revision() == existing.revision()) {
            return incoming != null && AppliedRevision.of(incoming).equals(existing)
                    ? new Reconciliation.AlreadyApplied(existing)
                    : new Reconciliation.ConflictingRevision(existing);
        }
        if (existing.changedState()) {
            return new Reconciliation.RebuildRequired(existing, affectedFrom(existing, observation));
        }
        return incoming == null
                ? new Reconciliation.NoChange()
                : new Reconciliation.Apply(Optional.of(existing));
    }

    /** The earliest sequence whose effect may be obsolete: the replaced revision's own, or earlier if declared. */
    private static long affectedFrom(AppliedRevision existing, RoutingObservation observation) {
        return observation.supersedesSequence() instanceof Feature.Known<Long> known
                ? Math.min(known.value(), existing.ledgerSequence())
                : existing.ledgerSequence();
    }

    private static void requireLedger(List<AppliedRevision> ledger) {
        if (ledger.size() > MAX_LEDGER) {
            throw new IllegalArgumentException(
                    "ledger allows at most " + MAX_LEDGER + " entries, got: " + ledger.size());
        }
        Set<String> references = new HashSet<>(ledger.size() * 2);
        for (AppliedRevision entry : ledger) {
            Objects.requireNonNull(entry, "ledger must not contain null");
            if (!references.add(entry.experienceRef())) {
                throw new IllegalArgumentException("ledger holds two entries for " + entry.experienceRef());
            }
        }
    }
}
