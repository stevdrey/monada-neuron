package monada.neuron.routing.outcome;

/** Why an observation does not (fully) support learning; declared in the fixed order in which reasons are reported. */
public enum IneligibilityReason {
    /** Identity, route, cohort or provenance of the outcome does not match the decision and request. */
    PROVENANCE_MISMATCH,
    /** The worker was unavailable or timed out; says nothing about the route. */
    ENVIRONMENTAL_FAILURE,
    /** Cancelled work. */
    CANCELLED,
    /** No current outcome, or the outcome is pending. */
    PENDING,
    /** Evidence was judged under another evaluation policy than the one in force. */
    STALE_EVALUATION_POLICY,
    /** The outcome was judged on an evidence revision that has since been replaced. */
    STALE_ARTIFACT_REVISION,
    /** Evidence ties at the same sequence with different states, or the invocation contradicts the accepted claim. */
    CONTRADICTORY_EVIDENCE,
    /** A mandatory dimension has no applicable effective observation. */
    MISSING_EVIDENCE,
    /** A mandatory dimension's effective observation is UNKNOWN. */
    UNKNOWN_EVIDENCE,
    /** The outcome is an imported claim, not validated under the evaluation policy. */
    IMPORTED_CLAIM,
    /** The result is not attributable to the executed route's stage (chain-level acceptance or no evidence). */
    NO_STAGE_ATTRIBUTION,
    /** Not accepted, but no proven mandatory failure exists: a refusal or unjudged failure is not a failure of the route. */
    NO_ATTRIBUTABLE_FAILURE
}
