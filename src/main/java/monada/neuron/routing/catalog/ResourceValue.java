package monada.neuron.routing.catalog;

import java.util.Objects;

/**
 * A host-supplied resource value: known, unknown or not measured. Unknown and not-measured are never zero, never
 * cheapest, never fastest; a known {@code 0} is a real zero. Neuron carries these values and does no arithmetic.
 */
public sealed interface ResourceValue permits ResourceValue.Known, ResourceValue.Unknown, ResourceValue.NotMeasured {

    /** How a known value was obtained. */
    enum Provenance {
        /** Reported by the provider or host. */
        REPORTED,
        /** Estimated by the host. */
        ESTIMATED
    }

    /**
     * A non-negative known value.
     *
     * @param value non-negative value in the estimate's unit
     * @param provenance how the value was obtained
     */
    record Known(long value, Provenance provenance) implements ResourceValue {

        /** Validates the value. */
        public Known {
            if (value < 0) {
                throw new IllegalArgumentException("value must be non-negative, got: " + value);
            }
            Objects.requireNonNull(provenance, "provenance must not be null");
        }
    }

    /** Reported but unmeasurable. */
    record Unknown() implements ResourceValue {
    }

    /** Absent. */
    record NotMeasured() implements ResourceValue {
    }
}
