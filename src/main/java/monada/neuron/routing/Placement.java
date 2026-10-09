package monada.neuron.routing;

/** What placed a ranked candidate relative to its neighbour. */
public enum Placement {
    /** It is the only eligible route. */
    ONLY_ELIGIBLE,
    /** Host tier decided. */
    TIER,
    /** Host fallback priority decided. */
    FALLBACK_PRIORITY,
    /** Learned preference decided. */
    LEARNED_PREFERENCE,
    /** The resource objective decided. */
    RESOURCE_OBJECTIVE,
    /** Every earlier rule tied; canonical route order decided. */
    ROUTE_ORDER
}
