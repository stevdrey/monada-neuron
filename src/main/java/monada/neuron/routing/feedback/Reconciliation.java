package monada.neuron.routing.feedback;

import java.util.Objects;
import java.util.Optional;

/**
 * How a newly derived sample revision relates to the caller's applied-revision ledger. It is advice for the caller,
 * who owns the ledger and the Nodes; nothing here mutates either.
 */
public sealed interface Reconciliation {

    /**
     * Apply the feedback and record it. A previous neutral entry of the same sample, if any, is replaced.
     *
     * @param replaces neutral entry the new record replaces, if any
     */
    record Apply(Optional<AppliedRevision> replaces) implements Reconciliation {

        /** Requires the optional. */
        public Apply {
            Objects.requireNonNull(replaces, "replaces must not be null");
            if (replaces.isPresent() && replaces.get().changedState()) {
                throw new IllegalArgumentException("only a neutral entry can be replaced without a rebuild");
            }
        }
    }

    /**
     * The revision derives no feedback, so no Node changes, but the caller records the marker
     * ({@link AppliedRevision#ofNoUpdate}) so a delayed older revision of the same sample stays stale.
     *
     * @param marker entry to record
     * @param replaces neutral entry the marker replaces, if any
     */
    record RecordOnly(AppliedRevision marker, Optional<AppliedRevision> replaces) implements Reconciliation {

        /** Validates the marker and the replaced entry. */
        public RecordOnly {
            Objects.requireNonNull(marker, "marker must not be null");
            Objects.requireNonNull(replaces, "replaces must not be null");
            if (replaces.isPresent() && replaces.get().changedState()) {
                throw new IllegalArgumentException("only a neutral entry can be replaced without a rebuild");
            }
            if (!(marker.basis() instanceof EntryBasis.NoUpdateMarker)) {
                throw new IllegalArgumentException("a record-only marker is neutral and names no cohort or Node, "
                        + "as AppliedRevision.ofNoUpdate builds it");
            }
        }
    }

    /** Duplicate delivery: this exact revision, identity and score is already applied. Do nothing. */
    record AlreadyApplied(AppliedRevision existing) implements Reconciliation {

        /** Requires the entry. */
        public AlreadyApplied {
            Objects.requireNonNull(existing, "existing must not be null");
        }
    }

    /** The same sample revision arrived with a different identity or score. Apply nothing; investigate. */
    record ConflictingRevision(AppliedRevision existing) implements Reconciliation {

        /** Requires the entry. */
        public ConflictingRevision {
            Objects.requireNonNull(existing, "existing must not be null");
        }
    }

    /** A revision older than the applied one arrived late. Apply nothing. */
    record StaleRevision(AppliedRevision existing) implements Reconciliation {

        /** Requires the entry. */
        public StaleRevision {
            Objects.requireNonNull(existing, "existing must not be null");
        }
    }

    /**
     * A newer revision supersedes a reward or penalty that was already applied. Node adaptation is not exactly
     * invertible, so the old effect cannot be subtracted: reset the partition's Nodes to the baseline and replay the
     * effective observations in {@link RebuildOrder} order.
     *
     * @param superseded the applied entry that the new revision replaces
     * @param affectedFromSequence earliest ledger sequence whose effect may be obsolete; a checkpoint is a valid
     *         starting point only if its {@code processedCutoff} is strictly below it
     */
    record RebuildRequired(AppliedRevision superseded, long affectedFromSequence) implements Reconciliation {

        /** Validates the entry and the sequence. */
        public RebuildRequired {
            Objects.requireNonNull(superseded, "superseded must not be null");
            if (affectedFromSequence < 0) {
                throw new IllegalArgumentException(
                        "affectedFromSequence must be non-negative, got: " + affectedFromSequence);
            }
        }
    }

    /**
     * The derivation found the observation foreign to this state (another mapping version, feature schema, scope or
     * evaluation policy) or wrongly paired with its decision. Nothing is applied and nothing is recorded, so it can
     * neither pollute this ledger, consume its capacity nor block the correct derivation of the same revision once
     * the caller fixes the pairing.
     *
     * @param reason why the derivation is not recorded
     */
    record NotRecorded(NoUpdateReason reason) implements Reconciliation {

        /** Requires a reason that is not recorded. */
        public NotRecorded {
            Objects.requireNonNull(reason, "reason must not be null");
            if (reason.recordable()) {
                throw new IllegalArgumentException(reason + " is recorded as a marker");
            }
        }
    }

    /**
     * The incoming derivation looked at an out-of-date view of the cost peer: it was refined against a revision of
     * the peer that the ledger has already superseded, or it is the same revision as a ledger entry and differs from
     * it only in the cost refinement (score, rule or peer). Nothing is applied: the caller derives the sample again
     * against the latest peer observation, so the incremental state equals a rebuild from the latest observations.
     *
     * @param ledgerEntry the ledger entry that shows the view is out of date: the newer peer revision, or the
     *         sample's own entry derived against another peer view
     */
    record StalePeerDependency(AppliedRevision ledgerEntry) implements Reconciliation {

        /** Requires the entry. */
        public StalePeerDependency {
            Objects.requireNonNull(ledgerEntry, "ledgerEntry must not be null");
        }
    }

    /** The ledger is full and the sample is new: nothing is applied or evicted. Compact through a checkpoint. */
    record CapacityExhausted() implements Reconciliation {
    }
}
