package monada.neuron.visual;

/**
 * The outcome of a visual factory or operation: a complete value or a typed rejection, never a partial result
 * (contract {@code visual-geometry/1} Section 7).
 *
 * <p>Malformed input (null, NaN, off grid) is a caller bug and throws {@link IllegalArgumentException} instead of
 * coming back as {@link Rejected}. Callers match on the two cases with a {@code switch}; there are no helper methods.
 *
 * @param <T> the value type of the successful case
 */
public sealed interface Result<T> permits Result.Ok, Result.Rejected {

    /**
     * A complete result.
     *
     * @param value the value; never null
     * @param <T> the value type
     */
    record Ok<T>(T value) implements Result<T> {

        public Ok {
            if (value == null) {
                throw new IllegalArgumentException("value must not be null");
            }
        }
    }

    /**
     * A valid input outside a limit.
     *
     * @param rejection why; never null
     * @param <T> the value type the operation would have returned
     */
    record Rejected<T>(VisualRejection rejection) implements Result<T> {

        public Rejected {
            if (rejection == null) {
                throw new IllegalArgumentException("rejection must not be null");
            }
        }
    }
}
