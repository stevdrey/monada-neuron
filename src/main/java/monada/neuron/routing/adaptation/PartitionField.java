package monada.neuron.routing.adaptation;

/** A part of an observation that places it in another state partition than the store's. */
public enum PartitionField {

    /** The decision's scope differs from the store's. */
    SCOPE,

    /** The outcome's evaluation policy id or version differs from the store's. */
    EVALUATION_POLICY,

    /** The outcome's cohort binding names another composite {@code mappingVersion}. */
    MAPPING_VERSION,

    /** The outcome's cohort binding names another feature schema. */
    FEATURE_SCHEMA,

    /** The outcome's cohort binding names another bucket mapping version. */
    BUCKET_MAPPING
}
