package monada.neuron.monad;

/** Deterministic terminal state of a successful reference cognitive cycle. */
public enum CognitiveCycleTermination {
    COMPLETED,
    NO_SIGNALS,
    CONTEXT_BUDGET_EXHAUSTED,
    STAGE_LIMIT_REACHED
}
