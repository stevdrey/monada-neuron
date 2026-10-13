package monada.neuron.routing.outcome;

/** Whether the outcome was asserted under the execution's evaluation policy or is an imported claim. */
public enum OutcomeOrigin {
    VALIDATED, IMPORTED_CLAIM
}
