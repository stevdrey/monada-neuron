package monada.neuron.reasoning.concept;

/** Why a prediction abstained, or why a descent stopped above a leaf (contract section 8.4). */
public enum AbstentionReason {
    /** The top score at a depth is below {@code τ_conf}, or the evidence carries no spectral energy. */
    LOW_CONFIDENCE,
    /** The top score minus the runner-up's at a depth is below {@code τ_margin}; exact ties land here. */
    LOW_MARGIN,
    /** The evidence pattern does not match the model's version or spectral frame. */
    INCOMPATIBLE_INPUT,
    /** The previous concept is not in the hierarchy. */
    UNKNOWN_CONCEPT,
    /** A comparison or level bound of the {@link PredictionBudget} was reached. */
    BUDGET_EXHAUSTED
}
