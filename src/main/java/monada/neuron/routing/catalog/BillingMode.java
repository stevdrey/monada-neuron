package monada.neuron.routing.catalog;

/** Host-declared billing mode of a route. It is a label only; Neuron never derives overflow or price from it. */
public enum BillingMode {
    /** Metered API usage. */
    API_METERED,
    /** Subscription-covered usage. */
    SUBSCRIPTION,
    /** Local execution. */
    LOCAL,
    /** The host does not know. */
    UNKNOWN
}
