package monada.neuron.routing;

/** Why a preference snapshot failed admission, declared in the contract's fixed reporting order (section 3.2). */
public enum StateMismatch {
    /** The snapshot belongs to another scope. */
    SCOPE_MISMATCH,
    /** The snapshot was built for another feature schema. */
    FEATURE_SCHEMA_MISMATCH,
    /** The snapshot is partitioned by another evaluation policy. */
    EVALUATION_POLICY_MISMATCH,
    /** The snapshot was built under another state-transition definition. */
    MAPPING_VERSION_MISMATCH,
    /** The snapshot's watermark is newer than the request cutoff. */
    PROCESSED_CUTOFF_NEWER
}
