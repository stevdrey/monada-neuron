package monada.neuron.routing.catalog;

/** Host-set overflow classification, independent of billing mode and tier. */
public enum OverflowClass {
    /** Ordinary route. */
    STANDARD,
    /** Route that requires explicit host permission to be considered. */
    OVERFLOW
}
