package monada.neuron.evaluation.wave;

import monada.neuron.resonance.ComparisonResult;
import monada.neuron.resonance.SpectralResonanceDynamics;
import monada.neuron.spectral.DecompositionResult;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.HaarWaveDecomposer;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.WaveDecomposer;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Per-window feature of the ablation's reference scorer ({@link FeatureConceptScorer}, issue #130): which transform
 * the window goes through and which unit vector the scorer compares with the concept centroids. Only the feature
 * changes between the arms that use it, so a difference between them is a difference of features.
 *
 * <ul>
 *   <li>{@link SpectralBasis#DFT}: the magnitude per bin {@code 0..floor(N/2)}, scaled to unit norm, the same rule as
 *       the core predictor's phase-blind feature, so with no coherence the scorer reproduces control C exactly
 *       (check {@code d.reference-equivalence}).</li>
 *   <li>{@link SpectralBasis#HAAR}: the magnitude of every coefficient of a complete Haar pattern of depth
 *       {@code haarLevels}, at its slot in the pattern (approximation and every detail level, {@code N} slots),
 *       scaled to unit norm. The explicit {@code new HaarWaveDecomposer(haarLevels)} is control D's construction
 *       (contract section 14.2); nothing selects wavelets by default.</li>
 *   <li>A positive {@code coherenceWeight} appends one dimension: the {@link SpectralResonanceDynamics} phase
 *       coherence between the window and the <b>previous window of its sequence</b> (input samples, never a label),
 *       times the weight, after the magnitudes are scaled to unit norm; the whole vector is then scaled to unit norm
 *       again. It is {@code 0} at a sequence start. Coherence is the phase-sensitive quantity of section 7; gate G2
 *       found it uninformative as a pair score, and this arm re-tests it as a feature.</li>
 * </ul>
 *
 * <p>Normalization prescales by the exponent of the largest value, as the core feature does, so the norm neither
 * overflows nor underflows. A silent window gives no feature.
 *
 * @param basis transform of the window
 * @param haarLevels Haar depth {@code L}, {@code 0} for the DFT
 * @param coherenceWeight weight of the coherence dimension, {@code 0} for none
 */
record ConceptFeatureMap(SpectralBasis basis, int haarLevels, double coherenceWeight) {

    /** Validates the combination. */
    ConceptFeatureMap {
        Objects.requireNonNull(basis, "basis must not be null");
        if ((basis == SpectralBasis.DFT) != (haarLevels == 0) || haarLevels < 0) {
            throw new IllegalArgumentException("Haar needs a positive depth and the DFT none, got: " + basis + ", "
                    + haarLevels);
        }
        if (!(coherenceWeight >= 0.0) || !Double.isFinite(coherenceWeight)) {
            throw new IllegalArgumentException("coherenceWeight must be finite and non-negative, got: "
                    + coherenceWeight);
        }
    }

    /** The DFT magnitude feature of control C. */
    static ConceptFeatureMap dft() {
        return new ConceptFeatureMap(SpectralBasis.DFT, 0, 0.0);
    }

    /** Returns a short arm label such as {@code DFT}, {@code HAAR(L=5)} or {@code HAAR(L=5)+coh(0.5)}. */
    String label() {
        String base = basis == SpectralBasis.DFT ? "DFT" : "HAAR(L=" + haarLevels + ")";
        return coherenceWeight > 0.0 ? base + "+coh(" + String.format(Locale.ROOT, "%.2f", coherenceWeight) + ")"
                : base;
    }

    /** Returns whether the feature reads the previous window. */
    boolean usesPrevious() {
        return coherenceWeight > 0.0;
    }

    /** Returns a new decomposer for this feature's transform. */
    WaveDecomposer decomposer() {
        return basis == SpectralBasis.DFT ? new DftWaveDecomposer() : new HaarWaveDecomposer(haarLevels);
    }

    /** Returns the feature dimension for windows of {@code sampleCount} samples. */
    int dimension(int sampleCount) {
        int magnitudes = basis == SpectralBasis.DFT ? sampleCount / 2 + 1 : sampleCount;
        return magnitudes + (usesPrevious() ? 1 : 0);
    }

    /** Decomposes one window; a fixture window is never rejected, so a rejection is a harness bug. */
    SpectralPattern decompose(WaveDecomposer decomposer, SampledWave wave) {
        return switch (decomposer.decompose(wave)) {
            case DecompositionResult.Decomposed decomposed -> decomposed.pattern();
            case DecompositionResult.DecompositionRejected rejected ->
                    throw new IllegalStateException("a fixture window was rejected: " + rejected);
        };
    }

    /**
     * Writes the unit feature of {@code current} into {@code feature} (length {@link #dimension}).
     *
     * @param previous the previous window of the sequence, or {@code null} at a sequence start
     * @return {@code false} when the window is silent and {@code feature} was left all zero
     */
    boolean extract(SpectralPattern current, SpectralPattern previous, SpectralResonanceDynamics dynamics,
                    double[] feature) {
        Arrays.fill(feature, 0.0);
        double peak = 0.0;
        for (int i = 0; i < current.componentCount(); i++) {
            peak = Math.max(peak, current.magnitude(i));
        }
        if (peak == 0.0) {
            return false;
        }
        int magnitudes = feature.length - (usesPrevious() ? 1 : 0);
        if (basis == SpectralBasis.HAAR && current.componentCount() != magnitudes) {
            throw new IllegalStateException("a Haar feature needs a complete pattern of " + magnitudes
                    + " coefficients, got: " + current.componentCount());
        }
        for (int i = 0; i < current.componentCount(); i++) {
            feature[basis == SpectralBasis.DFT ? current.index(i) : i] = current.magnitude(i);
        }
        normalize(feature, 0, magnitudes);
        if (usesPrevious()) {
            double coherence = previous == null ? 0.0 : switch (dynamics.compare(previous, current)) {
                case ComparisonResult.SpectralComparison comparison -> comparison.coherence();
                case ComparisonResult.Incompatible incompatible ->
                        throw new IllegalStateException("windows of one sequence are incompatible: " + incompatible);
            };
            feature[magnitudes] = coherenceWeight * coherence;
            normalize(feature, 0, feature.length);
        }
        return true;
    }

    /**
     * Scales {@code values[offset, offset + length)} to unit Euclidean norm in place, by the core feature's rule: a
     * {@code 2^-e} prescale by the exponent of the largest value, the ascending sum of squares, then the division.
     *
     * @return the norm of the range before scaling, {@code 0} for a zero range
     */
    double normalize(double[] values, int offset, int length) {
        double peak = 0.0;
        for (int k = 0; k < length; k++) {
            peak = Math.max(peak, values[offset + k]);
        }
        if (peak == 0.0) {
            return 0.0;
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
        return Math.scalb(norm, exponent);
    }
}
