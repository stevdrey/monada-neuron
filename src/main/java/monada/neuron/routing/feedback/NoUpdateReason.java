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
    /** The supplied bindings belong to another scope. */
    BINDING_SCOPE_MISMATCH,
    /** The supplied bindings belong to another evaluation policy or state definition. */
    BINDING_PARTITION_MISMATCH,
    /** The caller bound no Node to the executed route's cohort. */
    NO_TARGET_BINDING
}
