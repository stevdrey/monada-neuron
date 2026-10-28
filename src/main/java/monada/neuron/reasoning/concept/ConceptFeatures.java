package monada.neuron.reasoning.concept;

import monada.neuron.spectral.SpectralPattern;

import java.util.Arrays;

/**
 * Phase-blind spectral feature of a DFT pattern: the magnitude per bin {@code 0..floor(N/2)} (dropped bins {@code 0}),
 * scaled to unit Euclidean norm. Phases are ignored because gate G2 was no-go (contract section 7): the resonance
 * aggregate and coherence are not used as features.
 *
 * <p>Magnitudes are scaled by {@code 2^-e}, {@code e} the exponent of the largest one, before squaring (exact, as in
 * {@code SpectralArithmetic}), so the norm neither overflows nor underflows. A silent pattern gives the zero vector.
 */
final class ConceptFeatures {

    private ConceptFeatures() {
    }

    /**
     * Writes the unit feature of {@code pattern} into {@code feature}, whose length is the frame's bin count.
     *
     * @return {@code false} when the pattern is silent and {@code feature} was left all zero
     */
    static boolean extract(SpectralPattern pattern, double[] feature) {
        Arrays.fill(feature, 0.0);
        double peak = 0.0;
        for (int i = 0; i < pattern.componentCount(); i++) {
            peak = Math.max(peak, pattern.magnitude(i));
        }
        if (peak == 0.0) {
            return false;
        }
        int exponent = Math.getExponent(peak);
        double sum = 0.0;
        for (int i = 0; i < pattern.componentCount(); i++) {
            double scaled = Math.scalb(pattern.magnitude(i), -exponent);
            feature[pattern.index(i)] = scaled;
            sum += scaled * scaled;
        }
        double norm = StrictMath.sqrt(sum);
        for (int k = 0; k < feature.length; k++) {
            feature[k] /= norm;
        }
        return true;
    }

    /** Returns the dot product in ascending bin order. */
    static double dot(double[] feature, double[] centroids, int offset) {
        double sum = 0.0;
        for (int k = 0; k < feature.length; k++) {
            sum += feature[k] * centroids[offset + k];
        }
        return sum;
    }
}
