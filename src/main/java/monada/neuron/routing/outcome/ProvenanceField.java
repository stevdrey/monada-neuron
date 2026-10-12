package monada.neuron.routing.outcome;

/** The part of an outcome that disagrees with its decision or request. */
public enum ProvenanceField {
    CONTRACT_VERSION, DECISION_REF, EXECUTED_ROUTE, COHORT_BINDING, CATALOG_VERSION, ROUTING_POLICY, SOURCE_FINGERPRINT,
    CONTEXT_FINGERPRINT, CONSTRAINTS_FINGERPRINT, EVALUATION_POLICY, EVIDENCE_REFERENCE
}
