package monada.neuron.visual;

/**
 * The coordinate grid of contract {@code visual-geometry/1} Section 4.2: every shape parameter is {@code k / 4096}
 * with {@code |k| <= 2^28}.
 *
 * <p>Multiplying by {@code 4096} is exact in {@code double}, so the grid test adds no rounding of its own. The
 * numerator bound keeps every edge length and shoelace sum computed on numerators inside a {@code long}
 * (differences at most {@code 2^29}, products at most {@code 2^58}).
 */
final class DyadicGrid {

    static final double STEPS_PER_UNIT = 4096.0;
    static final long MAX_NUMERATOR = 1L << 28;

    private DyadicGrid() {
    }

    static boolean isOnGrid(double value) {
        double scaled = value * STEPS_PER_UNIT;
        return scaled == Math.rint(scaled) && Math.abs(scaled) <= MAX_NUMERATOR;
    }

    /** Nearest grid value, ties to even (Section 4.8 item 1). */
    static double snap(double value) {
        return Math.rint(value * STEPS_PER_UNIT) / STEPS_PER_UNIT;
    }

    /** The numerator {@code k} of an on grid value {@code k / 4096}. */
    static long numerator(double value) {
        return (long) (value * STEPS_PER_UNIT);
    }

    static double ofNumerator(long numerator) {
        return numerator / STEPS_PER_UNIT;
    }

    /** Returns the value with {@code -0.0} stored as {@code +0.0}, or throws when it is off the grid. */
    static double requireOnGrid(double value, String name) {
        if (!isOnGrid(value)) {
            throw new IllegalArgumentException(
                    name + " must be a finite multiple of 2^-12 within [-65536, 65536], was " + value);
        }
        return value + 0.0;
    }
}
