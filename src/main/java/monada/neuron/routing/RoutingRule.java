package monada.neuron.routing;

/** The lexicographic ordering rules of policy {@code route-lex} v1, in application order (contract section 3.3). */
public enum RoutingRule {
    /** Host tier, ascending. */
    TIER,
    /** Host fallback priority from the catalog snapshot, ascending. */
    FALLBACK_PRIORITY,
    /** Learned preference, descending; applied only with enough supporting observations for every candidate. */
    LEARNED_PREFERENCE,
    /** The policy's resource objective over known, comparable estimates. */
    RESOURCE_OBJECTIVE,
    /** Canonical route order, the total final tie-breaker. */
    ROUTE_ORDER
}
