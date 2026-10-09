package monada.neuron.routing.catalog;

/** Structured exclusion reasons, declared in the fixed precedence order of the contract (section 3.2). */
public enum EligibilityReason {
    /** The request's stage kind is not listed by the route. */
    STAGE_INCOMPATIBLE,
    /** The snapshot reports the route unavailable. */
    ROUTE_UNAVAILABLE,
    /** The snapshot does not know the route's availability. */
    AVAILABILITY_UNKNOWN,
    /** No permitted execution mode is offered by the route. */
    MODE_NOT_PERMITTED,
    /** The route's locality is absent or not allowed. */
    LOCALITY_NOT_PERMITTED,
    /** The route is an overflow route and overflow is not permitted. */
    OVERFLOW_NOT_PERMITTED,
    /** A required capability or tool is missing. */
    MISSING_CAPABILITY,
    /** A known limit is below what the request requires. */
    REQUIRED_LIMIT_EXCEEDED,
    /** A required limit is unknown. */
    REQUIRED_LIMIT_UNKNOWN
}
