package monada.neuron.resonance.adapter.experience;

/** Why one exported sample did not become a {@link monada.neuron.routing.outcome.RoutingObservation}. */
public enum RejectionReason {
    /** The sample belongs to another scope than the one requested. */
    SCOPE_MISMATCH,
    /** No stage of the execution carries a routing envelope reference. */
    MISSING_ENVELOPE_REFERENCE,
    /** More than one stage carries a routing envelope reference, so the routed stage is ambiguous. */
    AMBIGUOUS_ROUTED_STAGE,
    /** The host resolver has no envelope for the reference. */
    ENVELOPE_UNRESOLVED,
    /** The resolved envelope does not match the recorded digest (or none was recorded). */
    ENVELOPE_DIGEST_MISMATCH,
    /** The envelope names another scope, task, execution, attempt or stage. */
    ENVELOPE_IDENTITY_MISMATCH,
    /** The envelope descriptor differs from the recorded worker, provider, model, effort or billing mode. */
    ENVELOPE_ROUTE_MISMATCH,
    /** The envelope has no cohort binding. */
    MISSING_COHORT_BINDING,
    /** The execution has no recorded outcome. */
    MISSING_OUTCOME,
    /** The routed attempt has not finished, so its invocation status is not known. */
    ROUTED_ATTEMPT_UNFINISHED,
    /** The sample violates a bound or a neutral-record rule (for example too many observations). */
    MALFORMED_SAMPLE,
    /** A hypothetical-cost annotation violates the neutral-record rules. */
    INVALID_ANNOTATION,
    /** The execution was already seen earlier in this import. */
    DUPLICATE_OR_CONFLICTING_VERSION
}
