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

    /** The revision derives no feedback and the ledger holds nothing it could contradict: do nothing. */
    record NoChange() implements Reconciliation {
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

    /** The ledger is full and the sample is new: nothing is applied or evicted. Compact through a checkpoint. */
    record CapacityExhausted() implements Reconciliation {
    }
}
