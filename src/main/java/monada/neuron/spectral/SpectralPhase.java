package monada.neuron.spectral;

/**
 * Canonical phase rule of the spectral contract: phases are radians in {@code (-π, π]}.
 *
 * <p>{@code -π} is represented as {@code π} and {@code -0.0} as {@code 0.0}, so equal phases are bit-identical and
 * patterns built from them are equal values.
 */
public final class SpectralPhase {

    /** Upper bound (inclusive) of a canonical phase. */
    public static final double PI = StrictMath.PI;

    private static final double TWO_PI = 2.0 * StrictMath.PI;

    private SpectralPhase() {
    }

    /**
     * Wraps a finite phase into {@code (-π, π]} deterministically.
     *
     * @param phase finite phase in radians
     * @return the canonical phase
     * @throws IllegalArgumentException if {@code phase} is NaN or infinite
     */
    public static double wrap(double phase) {
        if (!Double.isFinite(phase)) {
            throw new IllegalArgumentException("phase must be finite, got: " + phase);
        }
        double wrapped = StrictMath.IEEEremainder(phase, TWO_PI);
        if (wrapped <= -PI) {
            return PI;
        }
        return wrapped == 0.0 ? 0.0 : wrapped;
    }

    /**
     * Returns whether a phase is already canonical: finite, in {@code (-π, π]} and not {@code -0.0}.
     *
     * @param phase phase in radians
     * @return {@code true} when {@code phase} needs no wrapping
     */
    public static boolean isCanonical(double phase) {
        return phase > -PI && phase <= PI && Double.doubleToRawLongBits(phase) != Double.doubleToRawLongBits(-0.0);
    }
}
