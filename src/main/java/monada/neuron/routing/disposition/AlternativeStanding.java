package monada.neuron.routing.disposition;

/**
 * What the decision itself proves about a route a host proposed instead of the advice. Derived from the decision,
 * never supplied by the host, and never a statement about eligibility now: {@link #HOST_CLAIM} means "the decision
 * does not prove this route eligible", not "ineligible".
 *
 * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization and
 * not quality evidence, and it awards no credit.
 */
public enum AlternativeStanding {
    /** The route is a ranked candidate of the selection, or an eligible route of the abstention. */
    ELIGIBLE_IN_DECISION,
    /** The route is one of the decision's exclusions. */
    EXCLUDED_IN_DECISION,
    /** The decision lists the route nowhere: cut by truncation, absent from the catalog, or a different version. */
    HOST_CLAIM
}
