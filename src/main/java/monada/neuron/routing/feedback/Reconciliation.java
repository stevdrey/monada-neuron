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
            if (marker.changedState() || marker.cohortKey().isPresent() || marker.targetNodeId().isPresent()
                    || marker.markerProvenance().isEmpty()) {
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
     * The observation belongs to another state partition (another mapping version, scope or evaluation policy than the
     * one the policy and bindings describe). Nothing is applied and nothing is recorded, so a foreign partition can
     * neither pollute this ledger nor consume its capacity.
     *
     * @param reason why the derivation found the observation foreign
     */
    record OutOfPartition(NoUpdateReason reason) implements Reconciliation {

        /** Requires a partition reason. */
        public OutOfPartition {
            Objects.requireNonNull(reason, "reason must not be null");
            if (!reason.foreignPartition()) {
                throw new IllegalArgumentException(reason + " is not a partition mismatch");
            }
        }
    }

    /**
     * The incoming reward was refined against a revision of its cost peer that the ledger has already superseded.
     * Nothing is applied: the caller derives the sample again against the latest peer observation, so the incremental
     * state equals a rebuild from the latest observations.
     *
     * @param peerEntry the ledger entry that holds a newer revision of the peer
     */
    record StalePeerDependency(AppliedRevision peerEntry) implements Reconciliation {

        /** Requires the entry. */
        public StalePeerDependency {
            Objects.requireNonNull(peerEntry, "peerEntry must not be null");
        }
    }

    /** The ledger is full and the sample is new: nothing is applied or evicted. Compact through a checkpoint. */
    record CapacityExhausted() implements Reconciliation {
    }
}
