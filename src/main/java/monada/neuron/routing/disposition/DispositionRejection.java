package monada.neuron.routing.disposition;

/**
 * Why a disposition factory refused to bind. Declaration order is the check order: identity first, then the variant
 * rule, then the route rule, and the first failure wins.
 *
 * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization and
 * not quality evidence, and it awards no credit.
 */
public enum DispositionRejection {
    /** The {@code DecisionRef} the host expected differs from the receipt's in at least one field. */
    REFERENCE_MISMATCH,
    /** Only a {@code Selected} decision can be followed; the receipt holds an abstention or no eligible route. */
    ADVICE_NOT_SELECTED,
    /** The proposed route is the advised route itself, so it is not an alternative. */
    ALTERNATIVE_EQUALS_ADVICE
}
