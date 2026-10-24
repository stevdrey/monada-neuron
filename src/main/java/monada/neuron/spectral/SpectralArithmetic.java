package monada.neuron.spectral;

/**
 * Shared numeric rules of the spectral package, so producers and {@link SpectralPattern} round alike.
 *
 * <p>Energies are summed with values scaled by {@code 2^-e}, {@code e} the exponent of the largest magnitude, and the
 * sum is scaled back by {@code 2^(2e)}. Power-of-two scaling is exact, so the result is bit-identical to the unscaled
 * sum whenever that sum neither underflows nor overflows, and a representable (possibly subnormal) energy is not lost
 * to squaring underflow. The Parseval check of {@link SpectralPattern} compares two sums built with this rule.
 */
final class SpectralArithmetic {

    private SpectralArithmetic() {
    }

    /** Returns {@code +0.0} for either signed zero and {@code value} otherwise. */
    static double canonicalZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }

    /** Returns {@code max |values[i]|}, {@code 0} for an empty or all-zero array. */
    static double peakMagnitude(double[] values) {
        double peak = 0.0;
        for (double value : values) {
            peak = Math.max(peak, Math.abs(value));
        }
        return peak;
    }

    /** Returns the scaling exponent for a nonzero peak magnitude. */
    static int scaleExponent(double peak) {
        return Math.getExponent(peak);
    }

    /** Returns {@code (value · 2^-exponent)²}. */
    static double scaledSquare(double value, int exponent) {
        double scaled = Math.scalb(value, -exponent);
        return scaled * scaled;
    }

    /** Returns a scaled sum of squares in the original scale: {@code scaledEnergy · 2^(2·exponent)}. */
    static double unscaleEnergy(double scaledEnergy, int exponent) {
        return Math.scalb(scaledEnergy, 2 * exponent);
    }

    /** Returns {@code Σ values[i]²} in ascending order with the scaling rule; {@code 0} when {@code peak} is 0. */
    static double sumOfSquares(double[] values, double peak) {
        if (peak == 0.0) {
            return 0.0;
        }
        int exponent = scaleExponent(peak);
        double sum = 0.0;
        for (double value : values) {
            sum += scaledSquare(value, exponent);
        }
        return unscaleEnergy(sum, exponent);
    }
}
