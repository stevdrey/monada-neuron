package monada.neuron.routing.adaptation.recovery;

import monada.neuron.routing.adaptation.RestoreProblem;
import monada.neuron.routing.adaptation.codec.CheckpointRejectReason;

import java.util.Objects;

/**
 * One reason a checkpoint cannot be replayed, with its {@link BlockerKind}. A blocker carries typed components only
 * (a reason, a store problem), never message text; the store problem's {@code message()} is the store's exception text
 * and is not produced here.
 */
public sealed interface RecoveryBlocker {

    /** The kind, which fixes this blocker's place in a {@link RecoveryAssessment.Blocked} list. */
    BlockerKind kind();

    /**
     * The bytes are not a canonical checkpoint.
     *
     * @param reason the codec's first failure
     */
    record Undecodable(CheckpointRejectReason reason) implements RecoveryBlocker {

        /** Requires the reason. */
        public Undecodable {
            Objects.requireNonNull(reason, "reason must not be null");
        }

        @Override
        public BlockerKind kind() {
            return BlockerKind.UNDECODABLE;
        }
    }

    /** The host has no export to replay. */
    record ExportUnavailable() implements RecoveryBlocker {

        @Override
        public BlockerKind kind() {
            return BlockerKind.EXPORT_UNAVAILABLE;
        }
    }

    /**
     * A rule of the target store, exactly as {@code RoutingStateStore.rebuildFrom} would apply it.
     *
     * @param problem the store's problem
     */
    record FromStore(RestoreProblem problem) implements RecoveryBlocker {

        /** Requires the problem. */
        public FromStore {
            Objects.requireNonNull(problem, "problem must not be null");
        }

        @Override
        public BlockerKind kind() {
            return switch (problem) {
                case RestoreProblem.Mismatch mismatch -> switch (mismatch.field()) {
                    case SCOPE -> BlockerKind.FOREIGN_SCOPE;
                    case EVALUATION_POLICY -> BlockerKind.FOREIGN_EVALUATION_POLICY;
                    case MAPPING_VERSION -> BlockerKind.MAPPING_VERSION_MISMATCH;
                    case FEATURE_SCHEMA -> BlockerKind.FEATURE_SCHEMA_MISMATCH;
                    // the record refuses it, so this arm only keeps the switch exhaustive
                    case BUCKET_MAPPING -> BlockerKind.BUCKET_MAPPING_MISMATCH;
                };
                case RestoreProblem.BucketMappingMismatch ignored -> BlockerKind.BUCKET_MAPPING_MISMATCH;
                case RestoreProblem.ChannelOutOfBounds ignored -> BlockerKind.CHANNEL_OUT_OF_BOUNDS;
                case RestoreProblem.ExportBehindCheckpoint ignored -> BlockerKind.EXPORT_BEHIND_CHECKPOINT;
                case RestoreProblem.ExportBehindStore ignored -> BlockerKind.EXPORT_BEHIND_STORE;
                case RestoreProblem.CohortBoundAfterCheckpoint ignored -> BlockerKind.COHORT_BOUND_AFTER_CHECKPOINT;
                case RestoreProblem.CohortCapacity ignored -> BlockerKind.COHORT_CAPACITY;
            };
        }
    }
}
