package monada.neuron.routing.adaptation;

import monada.neuron.routing.feedback.CohortKey;

import java.util.Objects;

/**
 * One reason {@link RoutingStateStore#rebuildFrom} would refuse to start from a checkpoint on a given store, as a value
 * (issue #101). {@link RoutingStateStore#restoreProblems} lists them in the order {@code rebuildFrom} checks them, at
 * most one per rule, and {@code rebuildFrom} throws {@link #message()} of the first; {@link RoutingStateStore#compact}
 * applies the partition and restorability rules the same way. So a preflight and the restore share one rulebook.
 *
 * <p>{@link #message()} is the store's exception text and may echo host tokens; a host that must not log them reads the
 * typed components instead.
 */
public sealed interface RestoreProblem {

    /** The text {@code rebuildFrom} throws for this problem. */
    String message();

    /**
     * The checkpoint's scope, evaluation policy, composite mapping version or feature schema differs from the store's.
     * One {@link PartitionField#EVALUATION_POLICY} covers the id and the version, as it does for an observation.
     *
     * @param field the differing part, never {@link PartitionField#BUCKET_MAPPING} (see {@link BucketMappingMismatch})
     */
    record Mismatch(PartitionField field) implements RestoreProblem {

        /** Requires a field the checkpoint identity carries. */
        public Mismatch {
            Objects.requireNonNull(field, "field must not be null");
            if (field == PartitionField.BUCKET_MAPPING) {
                throw new IllegalArgumentException("a bucket mapping mismatch is a BucketMappingMismatch");
            }
        }

        @Override
        public String message() {
            return "the checkpoint belongs to another partition or definition";
        }
    }

    /**
     * A retained marker was recorded under another bucket mapping than the definition's, so it could answer a redelivery
     * this definition would bucket differently.
     *
     * @param experienceRef the first such marker's sample reference
     * @param found the marker's bucket mapping version
     * @param expected the definition's bucket mapping version
     */
    record BucketMappingMismatch(String experienceRef, String found, String expected) implements RestoreProblem {

        /** Requires the parts. */
        public BucketMappingMismatch {
            Objects.requireNonNull(experienceRef, "experienceRef must not be null");
            Objects.requireNonNull(found, "found must not be null");
            Objects.requireNonNull(expected, "expected must not be null");
        }

        @Override
        public String message() {
            return "marker " + experienceRef + " was recorded under bucket mapping " + found + ", not " + expected;
        }
    }

    /**
     * A cohort's Node channel lies outside the definition's adaptation bounds.
     *
     * @param key the first such cohort
     */
    record ChannelOutOfBounds(CohortKey key) implements RestoreProblem {

        /** Requires the key. */
        public ChannelOutOfBounds {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public String message() {
            return "cohort " + key + " holds a Node channel outside the definition's adaptation bounds";
        }
    }

    /**
     * The export's high watermark is below the checkpoint's cutoff: the export does not reach what the checkpoint
     * already contains.
     *
     * @param exportCutoff the export's high watermark
     * @param checkpointCutoff the checkpoint's cutoff
     */
    record ExportBehindCheckpoint(long exportCutoff, long checkpointCutoff) implements RestoreProblem {

        /**
         * Requires the export behind the checkpoint, as the store reports it. The export cutoff is host input and may be
         * negative; {@code rebuildFrom} reports it with the same text it always has.
         */
        public ExportBehindCheckpoint {
            requireNonNegative("checkpointCutoff", checkpointCutoff);
            if (exportCutoff >= checkpointCutoff) {
                throw new IllegalArgumentException("exportCutoff " + exportCutoff + " is not behind checkpointCutoff "
                        + checkpointCutoff);
            }
        }

        @Override
        public String message() {
            return "exportCutoff " + exportCutoff + " is below the checkpoint's cutoff " + checkpointCutoff;
        }
    }

    /**
     * The export's high watermark is below the store's own watermark: the rebuilt state would forget observations the
     * store already examined.
     *
     * @param exportCutoff the export's high watermark
     * @param processedCutoff the store's watermark
     */
    record ExportBehindStore(long exportCutoff, long processedCutoff) implements RestoreProblem {

        /** Requires the export behind the store, as the store reports it; the export cutoff is host input and may be negative. */
        public ExportBehindStore {
            requireNonNegative("processedCutoff", processedCutoff);
            if (exportCutoff >= processedCutoff) {
                throw new IllegalArgumentException("exportCutoff " + exportCutoff + " is not behind processedCutoff "
                        + processedCutoff);
            }
        }

        @Override
        public String message() {
            return "exportCutoff " + exportCutoff + " is below processedCutoff " + processedCutoff
                    + ": the rebuilt state would forget examined observations";
        }
    }

    /**
     * A cohort bound on the store but not held by the checkpoint is named by one of its retained
     * {@code NO_TARGET_BINDING} markers: the checkpoint records that sample as unbound where the store would apply it.
     * A checkpoint whose cutoff is below {@code markerSequence} does not retain the marker.
     *
     * @param key the first such cohort
     * @param experienceRef the naming marker's sample reference
     * @param markerSequence the naming marker's ledger sequence
     * @param checkpointCutoff the checkpoint's cutoff
     */
    record CohortBoundAfterCheckpoint(CohortKey key, String experienceRef, long markerSequence, long checkpointCutoff)
            implements RestoreProblem {

        /** Requires the parts, and a marker the checkpoint retains (its sequence at or below the cutoff). */
        public CohortBoundAfterCheckpoint {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(experienceRef, "experienceRef must not be null");
            requireNonNegative("markerSequence", markerSequence);
            if (markerSequence > checkpointCutoff) {
                throw new IllegalArgumentException("a checkpoint at " + checkpointCutoff + " does not retain marker "
                        + experienceRef + " at " + markerSequence);
            }
        }

        @Override
        public String message() {
            return "cohort " + key + " was bound after the checkpoint at " + checkpointCutoff + ", which records sample "
                    + experienceRef + " as unbound; rebuild from an earlier checkpoint or from scratch";
        }
    }

    /**
     * The checkpoint's cohorts, the store's and those the store's checkpoints hold together exceed
     * {@link RoutingStateStore#MAX_COHORTS}.
     *
     * @param union the size of that union
     * @param reservedOnly how many of them are held only by this store's checkpoints
     */
    record CohortCapacity(int union, int reservedOnly) implements RestoreProblem {

        /** Requires a union over the capacity, of which a non-negative part is reserved only. */
        public CohortCapacity {
            if (union <= RoutingStateStore.MAX_COHORTS) {
                throw new IllegalArgumentException("a union of " + union + " fits the " + RoutingStateStore.MAX_COHORTS
                        + " a rebuilt store allows");
            }
            if (reservedOnly < 0 || reservedOnly > union) {
                throw new IllegalArgumentException("reservedOnly must lie within 0 and " + union + ", got: "
                        + reservedOnly);
            }
        }

        @Override
        public String message() {
            return "the checkpoint's cohorts and this store's together are " + union
                    + (reservedOnly > 0 ? ", " + reservedOnly + " of them held only by this store's checkpoints" : "")
                    + ", more than the " + RoutingStateStore.MAX_COHORTS + " a rebuilt store allows";
        }
    }

    private static void requireNonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative, got: " + value);
        }
    }
}
