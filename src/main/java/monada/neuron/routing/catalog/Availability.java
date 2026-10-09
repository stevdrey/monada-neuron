package monada.neuron.routing.catalog;

/** Transient, host-supplied availability of a route in one catalog snapshot. Never part of the versioned descriptor. */
public enum Availability {
    /** The host reports the route as available. */
    AVAILABLE,
    /** The host reports the route as unavailable. */
    UNAVAILABLE,
    /** The host cannot say; treated as ineligible (fail closed). */
    UNKNOWN
}
