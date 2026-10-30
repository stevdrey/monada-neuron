package monada.neuron.routing.adaptation.recovery;

/**
 * Why a checkpoint cannot be replayed into a store. Declaration order is the report order of
 * {@link RecoveryAssessment.Blocked}: the codec, then the export's presence, then the store's own rules in the order
 * {@code RoutingStateStore.rebuildFrom} checks them.
 */
public enum BlockerKind {

    /** The bytes did not decode; see {@link RecoveryBlocker.Undecodable#reason()}. */
    UNDECODABLE,

    /** The host has no export to replay. */
    EXPORT_UNAVAILABLE,

    /** The checkpoint's scope differs from the store's. */
    FOREIGN_SCOPE,

    /** The checkpoint's evaluation policy id or version differs from the store's. */
    FOREIGN_EVALUATION_POLICY,

    /** The checkpoint's composite mapping version differs from the definition's. */
    MAPPING_VERSION_MISMATCH,

    /** The checkpoint's feature schema differs from the definition's. */
    FEATURE_SCHEMA_MISMATCH,

    /** A retained marker was recorded under another bucket mapping than the definition's. */
    BUCKET_MAPPING_MISMATCH,

    /** A cohort's Node channel lies outside the definition's adaptation bounds. */
    CHANNEL_OUT_OF_BOUNDS,

    /** The export's watermark is below the checkpoint's cutoff. */
    EXPORT_BEHIND_CHECKPOINT,

    /** The export's watermark is below the store's own watermark. */
    EXPORT_BEHIND_STORE,

    /** A cohort bound after the checkpoint is named by one of its retained {@code NO_TARGET_BINDING} markers. */
    COHORT_BOUND_AFTER_CHECKPOINT,

    /** The checkpoint's cohorts, the store's and its reservations together exceed the cohort bound. */
    COHORT_CAPACITY
}
