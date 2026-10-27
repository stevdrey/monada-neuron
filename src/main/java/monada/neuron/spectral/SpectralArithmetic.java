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

    private static final double TWO_PI = 2.0 * StrictMath.PI;

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

    /**
     * Returns whether a source energy can be carried by a pattern: silent samples ({@code peak == 0}), or an energy in
     * {@code [Double.MIN_NORMAL, SpectralPattern.MAX_SOURCE_ENERGY]}. Every decomposer applies this rule before it
     * builds a pattern and reports a failure as {@link DecompositionRejectionReason#ENERGY_OUT_OF_RANGE}.
     */
    static boolean sourceEnergyRepresentable(double peak, double energy) {
        return peak == 0.0 || energy >= Double.MIN_NORMAL && energy <= SpectralPattern.MAX_SOURCE_ENERGY;
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

    /** Returns {@code max |a[i] − b[i]|} over equal-length arrays, {@code 0} when they are equal. */
    static double peakDifference(double[] a, double[] b) {
        double peak = 0.0;
        for (int i = 0; i < a.length; i++) {
            peak = Math.max(peak, Math.abs(a[i] - b[i]));
        }
        return peak;
    }

    /**
     * Returns {@code √(Σ (a[i] − b[i])² / n)} with the scaling rule, evaluated in the scaled domain so neither the
     * squares nor the sum overflow; {@code 0} when {@code peak}, the {@link #peakDifference}, is {@code 0}.
     */
    static double rootMeanSquareDifference(double[] a, double[] b, double peak) {
        if (peak == 0.0) {
            return 0.0;
        }
        int exponent = scaleExponent(peak);
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += scaledSquare(a[i] - b[i], exponent);
        }
        return Math.scalb(StrictMath.sqrt(sum / a.length), exponent);
    }

    /**
     * Returns {@code |Σ a[i]² − Σ b[i]²| / Σ b[i]²} with both sums scaled by the exponent of their common peak, so the
     * ratio is neither lost to underflow nor overflowed (it is invariant under a common power-of-two scale); {@code 0}
     * when both arrays are silent. {@code b} must not be silent unless {@code a} is.
     */
    static double relativeEnergyDifference(double[] a, double[] b) {
        double peak = Math.max(peakMagnitude(a), peakMagnitude(b));
        if (peak == 0.0) {
            return 0.0;
        }
        int exponent = scaleExponent(peak);
        double first = 0.0;
        double second = 0.0;
        for (int i = 0; i < a.length; i++) {
            first += scaledSquare(a[i], exponent);
            second += scaledSquare(b[i], exponent);
        }
        return Math.abs(first - second) / second;
    }

    /**
     * Fills the DFT twiddle tables: {@code cos[m] = StrictMath.cos((2π · m) / N)} and the same for {@code sin}, for the
     * {@code N = cos.length} residues {@code m}. The decomposer and the synthesizer read the same values, so a round
     * trip uses one definition of every angle.
     */
    static void fillTwiddles(double[] cos, double[] sin) {
        int n = cos.length;
        for (int m = 0; m < n; m++) {
            double angle = (TWO_PI * m) / n;
            cos[m] = StrictMath.cos(angle);
            sin[m] = StrictMath.sin(angle);
        }
    }

    /**
     * Returns the largest difference allowed between two energies of magnitude {@code scale}: the larger of
     * {@link SpectralPattern#ENERGY_TOLERANCE} relative to {@code scale} and {@code roundingUlps} ULPs at
     * {@code scale}. The ULP term only dominates for (near-)subnormal energies, whose relative precision is reduced.
     */
    static double energyAllowance(double scale, double roundingUlps) {
        return Math.max(SpectralPattern.ENERGY_TOLERANCE * scale, roundingUlps * Math.ulp(scale));
    }
}
