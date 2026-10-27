package monada.neuron.routing.disposition;

/**
 * Which variant of advice a reference points at: one constant per {@code RoutingDecision} variant.
 *
 * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization and
 * not quality evidence, and it awards no credit.
 */
public enum AdviceKind {
    /** A {@code RoutingDecision.Selected}: exactly one advised route. */
    SELECTED,
    /** A {@code RoutingDecision.Abstain}: eligible routes exist but the policy declined to advise. */
    ABSTAINED,
    /** A {@code RoutingDecision.NoEligibleRoute}: the hard constraints excluded every route. */
    NO_ELIGIBLE_ROUTE
}
