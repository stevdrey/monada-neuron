package monada.neuron.routing.adaptation.recovery;

/**
 * What a host can do next after an assessment or a failed replay. Every action keeps the learning evidence: there is no
 * discard or reset. A host that decides to start cold does so by its own choice, outside this advice.
 */
public enum RecoveryAction {

    /** Call {@code RoutingStateStore.rebuildFrom} with the checkpoint over the export's replay window. */
    REPLAY_FROM_CHECKPOINT,

    /** Use a checkpoint whose cutoff is strictly below {@link RecoveryStep#cutoffBelow()}, then assess again. */
    USE_OLDER_CHECKPOINT,

    /** Call {@code RoutingStateStore.rebuild} over the full export; the checkpoint is not used, the export is. */
    REBUILD_FROM_SCRATCH,

    /** Obtain checkpoint bytes that decode, or a checkpoint of this partition and definition. */
    OBTAIN_READABLE_CHECKPOINT,

    /** Obtain an export, or one whose watermark reaches far enough. */
    OBTAIN_EXPORT,

    /** The store's definition or partition does not match what the checkpoint was taken for; check the host's setup. */
    FIX_HOST_CONFIGURATION,

    /** The replay stopped on an observation for a reason only the host can judge; see {@link RecoveryStep#update()}. */
    INSPECT_FAILED_OBSERVATION
}
