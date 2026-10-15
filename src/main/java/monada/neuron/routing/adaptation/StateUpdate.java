package monada.neuron.routing.adaptation;

import monada.neuron.routing.feedback.AppliedRevision;
import monada.neuron.routing.feedback.Reconciliation;

import java.util.List;
import java.util.Objects;

/**
 * What {@link RoutingStateStore#consume} did with one derivation. Every expected outcome is a value, never an
 * exception; only inconsistent caller data throws.
 */
public sealed interface StateUpdate {

    /** True when the observation was examined in this partition, so the store's {@code processedCutoff} advanced. */
    boolean examined();

    /**
     * The feedback was applied and recorded: a reward or penalty moved exactly its bound cohort Node (and its support
     * count); a neutral feedback moved nothing.
     *
     * @param entry the ledger entry written
     */
    record Applied(AppliedRevision entry) implements StateUpdate {

        /** Requires the entry. */
        public Applied {
            Objects.requireNonNull(entry, "entry must not be null");
        }

        @Override
        public boolean examined() {
            return true;
        }
    }

    /**
     * Nothing was derived; the marker was recorded so an older delivery of the sample stays stale. No Node moved.
     *
     * @param marker the ledger entry written
     */
    record Recorded(AppliedRevision marker) implements StateUpdate {

        /** Requires the marker. */
        public Recorded {
            Objects.requireNonNull(marker, "marker must not be null");
        }

        @Override
        public boolean examined() {
            return true;
        }
    }

    /**
     * Nothing changed except possibly the watermark: a duplicate delivery ({@code AlreadyApplied}) or an older
     * revision ({@code StaleRevision}) was examined; a {@code NotRecorded} derivation belongs to another partition
     * and was not examined.
     *
     * @param advice the reconciler's advice
     */
    record Unchanged(Reconciliation advice) implements StateUpdate {

        /** Requires one of the advices that leave the state as it is. */
        public Unchanged {
            Objects.requireNonNull(advice, "advice must not be null");
            if (!(advice instanceof Reconciliation.AlreadyApplied) && !(advice instanceof Reconciliation.StaleRevision)
                    && !(advice instanceof Reconciliation.NotRecorded)) {
                throw new IllegalArgumentException("not an unchanged advice: " + advice);
            }
        }

        @Override
        public boolean examined() {
            return !(advice instanceof Reconciliation.NotRecorded);
        }
    }

    /**
     * An already-applied effect is obsolete or out of order; it cannot be subtracted. Nothing was changed, including
     * the watermark. The caller rebuilds through {@link RoutingStateStore#rebuild}.
     *
     * @param advice the reconciler's advice
     */
    record RebuildRequired(Reconciliation.RebuildRequired advice) implements StateUpdate {

        /** Requires the advice. */
        public RebuildRequired {
            Objects.requireNonNull(advice, "advice must not be null");
        }

        @Override
        public boolean examined() {
            return false;
        }
    }

    /**
     * Nothing was changed, including the watermark: a {@code ConflictingRevision} to investigate, a
     * {@code StalePeerDependency} to derive again against the latest peer, or a full ledger ({@code CapacityExhausted}),
     * which is reported, never evicted or learned into another scope.
     *
     * @param advice the reconciler's advice
     */
    record Blocked(Reconciliation advice) implements StateUpdate {

        /** Requires one of the blocking advices. */
        public Blocked {
            Objects.requireNonNull(advice, "advice must not be null");
            if (!(advice instanceof Reconciliation.ConflictingRevision)
                    && !(advice instanceof Reconciliation.StalePeerDependency)
                    && !(advice instanceof Reconciliation.CapacityExhausted)) {
                throw new IllegalArgumentException("not a blocking advice: " + advice);
            }
        }

        @Override
        public boolean examined() {
            return false;
        }
    }

    /**
     * Only a store whose ledger was {@linkplain RoutingStateStore#compact compacted} (or rebuilt from a checkpoint)
     * answers this: the observation cannot be reconciled against the entries the checkpoint replaced by its digest.
     * Nothing was changed, including the watermark.
     *
     * <p>{@code COVERED} needs no action: it is a delivery at or below the horizon of a sample the checkpoint already
     * accounted for. The other kinds invalidate the checkpoint for this observation: the caller rebuilds from a
     * checkpoint whose cutoff is strictly below {@code affectedFromSequence}, or from scratch.
     *
     * @param kind why the observation is beyond the checkpoint
     * @param horizon the store's compaction horizon
     * @param affectedFromSequence the earliest position the observation affects: its {@code supersedesSequence} for
     *         {@code SUPERSEDES_COMPACTED}, otherwise its own ledger sequence
     */
    record BeyondCheckpoint(Kind kind, long horizon, long affectedFromSequence) implements StateUpdate {

        /** Requires the kind and positions consistent with it. */
        public BeyondCheckpoint {
            Objects.requireNonNull(kind, "kind must not be null");
            if (horizon < 0 || affectedFromSequence < 0) {
                throw new IllegalArgumentException("horizon and affectedFromSequence must be non-negative");
            }
            if (affectedFromSequence > horizon) {
                throw new IllegalArgumentException("an observation affecting only sequences after the horizon "
                        + horizon + " is not beyond the checkpoint, got: " + affectedFromSequence);
            }
        }

        @Override
        public boolean examined() {
            return false;
        }

        /** Why an observation is beyond the checkpoint. */
        public enum Kind {
            /** A delivery at or below the horizon of a sample whose entry was compacted: a repeat or an older one. */
            COVERED,
            /** A newer revision replaces one whose entry was compacted; its old effect is part of the checkpoint. */
            SUPERSEDES_COMPACTED,
            /**
             * A delivery at or below the horizon that would apply or record something there (a retried marker), which
             * only a replay from before that position can place in applied order.
             */
            BEFORE_HORIZON
        }
    }

    /**
     * The observation belongs to another state partition; nothing was changed or recorded.
     *
     * @param fields the differing parts, unique and in declaration order
     */
    record ForeignPartition(List<PartitionField> fields) implements StateUpdate {

        /** Requires a non-empty list. */
        public ForeignPartition {
            fields = List.copyOf(Objects.requireNonNull(fields, "fields must not be null"));
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("a foreign partition names at least one field");
            }
        }

        @Override
        public boolean examined() {
            return false;
        }
    }
}
