package monada.neuron.routing.features;

import java.util.Objects;

/**
 * A host-supplied feature value that is either measured or explicitly unknown.
 *
 * <p>An unknown value is a distinct state, never a measured zero, an empty set or a default.
 *
 * @param <T> value type of a known feature
 */
public sealed interface Feature<T> permits Feature.Known, Feature.Unknown {

    /**
     * A measured or declared value.
     *
     * @param value non-null value
     * @param <T> value type
     */
    record Known<T>(T value) implements Feature<T> {

        /** Requires a non-null value. */
        public Known {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * An explicitly unknown value.
     *
     * @param <T> value type
     */
    record Unknown<T>() implements Feature<T> {
    }

    /** Creates a known feature. */
    static <T> Feature<T> known(T value) {
        return new Known<>(value);
    }

    /** Creates an explicitly unknown feature. */
    static <T> Feature<T> unknown() {
        return new Unknown<>();
    }
}
