package monada.neuron.routing;

/** The only v1 abstention reasons (contract section 3.3). */
public enum AbstainReason {
    /** The policy needs the host to confirm a lower-priority tier before advising. */
    POLICY_TRADEOFF_UNRESOLVED,
    /** A route is eligible but the preference snapshot fails admission. */
    STATE_INCOMPATIBLE
}
