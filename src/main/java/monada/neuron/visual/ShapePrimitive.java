package monada.neuron.visual;

/**
 * An image independent shape description in unit square coordinates (contract {@code visual-geometry/1} Sections
 * 3.3 and 4.1): origin top left, {@code y} down, every parameter a multiple of {@code 2^-12}.
 *
 * <p>A primitive is geometry only. It carries no name, label or class beyond its record kind, and no color, alpha,
 * palette or texture. The {@code permits} order is the kind ordinal used by the composite canonical order: circle
 * {@code 0}, square {@code 1}, triangle {@code 2}, composite {@code 3}.
 *
 * <p>Every record constructor throws {@link IllegalArgumentException} for a malformed value (null, NaN, infinite,
 * off the grid, a numerator beyond {@code 2^28}, or degenerate), and stores {@code -0.0} as {@code +0.0}, so each
 * shape has exactly one stored representation and record equality is shape equality.
 */
public sealed interface ShapePrimitive permits Circle, Square, Triangle, Composite {
}
