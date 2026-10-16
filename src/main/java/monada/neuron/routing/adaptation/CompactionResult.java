package monada.neuron.routing.adaptation;

import java.util.Objects;

/** What {@link RoutingStateStore#compact} did. Compaction is all-or-nothing and never automatic. */
public sealed interface CompactionResult {

    /**
     * The ledger entries the checkpoint covers were folded into the store's digest and dropped, except retryable
     * markers; the checkpoint is now the store's {@link RoutingStateStore#latestCheckpoint()}.
     *
     * @param removed entries dropped from the ledger
     * @param horizon the compaction horizon, the checkpoint's {@code processedCutoff}
     */
    record Compacted(int removed, long horizon) implements CompactionResult {

        /** Requires a non-negative count and horizon. */
        public Compacted {
            if (removed < 0 || horizon < 0) {
                throw new IllegalArgumentException("removed and horizon must be non-negative");
            }
        }
    }

    /**
     * Nothing was changed.
     *
     * @param reason why the checkpoint cannot compact this store
     */
    record Refused(Reason reason) implements CompactionResult {

        /** Requires the reason. */
        public Refused {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** Why a compaction was refused. */
    enum Reason {
        /**
         * The ledger at or below the checkpoint's cutoff changed since it was taken (a late sample was recorded there
         * or an entry it covers was replaced), so it no longer describes this store; take a new checkpoint.
         */
        STALE,
        /** The checkpoint's cutoff is below the store's compaction horizon; compaction never moves backwards. */
        BEHIND_HORIZON,
        /**
         * An entry it would drop, or one already compacted, credits a cohort the checkpoint does not hold, so that
         * effect could never be restored; a rebuild without the cohort first turns its entries into markers.
         */
        CREDITS_UNBOUND_COHORT,
        /**
         * A rebuild from the checkpoint could not start on this store as it is now, so compaction would drop entries no
         * rebuild could recover: the checkpoint's cohorts and the store's together exceed the cohort bound, or a cohort
         * bound after the checkpoint is named by one of its retained {@code NO_TARGET_BINDING} markers. A newer
         * checkpoint describes the store as it is.
         */
        UNRESTORABLE
    }
}
