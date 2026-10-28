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

    /** Creates a stateless feature extractor. */
    ConceptFeatures() {
    }

    /**
     * Writes the unit feature of {@code pattern} into {@code feature}, whose length is the frame's bin count.
     *
     * @return {@code false} when the pattern is silent and {@code feature} was left all zero
     */
    boolean extract(SpectralPattern pattern, double[] feature) {
        Arrays.fill(feature, 0.0);
        double peak = 0.0;
        for (int i = 0; i < pattern.componentCount(); i++) {
            peak = Math.max(peak, pattern.magnitude(i));
        }
        if (peak == 0.0) {
            return false;
        }
        for (int i = 0; i < pattern.componentCount(); i++) {
            feature[pattern.index(i)] = pattern.magnitude(i);
        }
        normalize(feature, 0, feature.length);
        return true;
    }

    /**
     * Scales {@code values[offset, offset + length)} to unit Euclidean norm in place: a {@code 2^-e} prescale by the
     * exponent of the largest value, then the ascending sum of squares. A zero range stays zero. The trainer uses it
     * for centroids, so features and centroids are normalized by one rule.
     */
    void normalize(double[] values, int offset, int length) {
        double peak = 0.0;
        for (int k = 0; k < length; k++) {
            peak = Math.max(peak, values[offset + k]);
        }
        if (peak == 0.0) {
            return;
        }
        int exponent = Math.getExponent(peak);
        double sum = 0.0;
        for (int k = 0; k < length; k++) {
            double scaled = Math.scalb(values[offset + k], -exponent);
            values[offset + k] = scaled;
            sum += scaled * scaled;
        }
        double norm = StrictMath.sqrt(sum);
        for (int k = 0; k < length; k++) {
            values[offset + k] /= norm;
        }
    }

    /** Returns the dot product in ascending bin order. */
    double dot(double[] feature, double[] centroids, int offset) {
        double sum = 0.0;
        for (int k = 0; k < feature.length; k++) {
            sum += feature[k] * centroids[offset + k];
        }
        return sum;
    }
}
