package monada.neuron.routing.feedback;

/** Why no feedback is derived at all: the caller records and applies nothing for the sample. */
public enum NoUpdateReason {
    /** The outcome does not answer this decision and request; the assessment lists every differing field. */
    PROVENANCE_MISMATCH,
    /** The outcome was judged on an evidence revision that has since been replaced. */
    STALE_ARTIFACT_REVISION,
    /** Evidence contradicts itself, or the invocation contradicts the accepted claim. */
    CONTRADICTORY_EVIDENCE,
    /** A validated or failed result that is not attributable to the executed route's own stage (chain-only). */
    NO_STAGE_ATTRIBUTION,
    /** The policy's state definition differs from the one the decision was made under. */
    MAPPING_MISMATCH,
    /** The decision was made under another feature schema than the state definition's. */
    FEATURE_SCHEMA_MISMATCH,
    /** The supplied bindings belong to another scope. */
    BINDING_SCOPE_MISMATCH,
    /** The supplied bindings belong to another evaluation policy or state definition. */
    BINDING_PARTITION_MISMATCH,
    /** The caller bound no Node to the executed route's cohort. */
    NO_TARGET_BINDING;

    /**
     * True when a no-update of this kind is recorded in the ledger as a marker. It is not recorded when the
     * observation belongs to another state partition (mapping version, feature schema, scope or evaluation policy than
     * the policy and bindings describe): such a result says nothing about any sample of this state, so recording it
     * would pollute the ledger and consume its capacity; the caller routes a sample to the ledger of its partition.
     * A {@code PROVENANCE_MISMATCH} is recorded: it is a fact about this sample revision (its outcome does not match
     * its decision or request), so it must retract an older reward and keep an older delivery stale, and a corrected
     * pairing of the same revision may replace the marker (see {@code AppliedRevision#retryable()}).
     */
    public boolean recordable() {
        return switch (this) {
            case PROVENANCE_MISMATCH, STALE_ARTIFACT_REVISION, CONTRADICTORY_EVIDENCE, NO_STAGE_ATTRIBUTION,
                    NO_TARGET_BINDING -> true;
            case MAPPING_MISMATCH, FEATURE_SCHEMA_MISMATCH, BINDING_SCOPE_MISMATCH, BINDING_PARTITION_MISMATCH -> false;
        };
    }
}
