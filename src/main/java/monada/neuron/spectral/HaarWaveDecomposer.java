package monada.neuron.spectral;

import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.DecompositionResult.DecompositionRejected;

/**
 * Scalar reference multiresolution decomposer: the orthonormal Haar wavelet of contract {@code ncm-wave-cognition/1}
 * sections 2.1, 5.2 and 6 (ADR 0037).
 *
 * <p><b>Definition.</b> Each level halves the current approximation: {@code a_m = (x_{2m} + x_{2m+1}) / √2} and
 * {@code d_m = (x_{2m} − x_{2m+1}) / √2}, first over the samples (level {@code 1}), then over the previous
 * approximation, for levels {@code 1..L}. {@code L} is fixed per decomposer, so every pattern it produces has the same
 * {@link SpectralScale} and patterns of equal sample count share a frame. The time origin is the first sample of the
 * window.
 *
 * <p><b>Layout and semantics.</b> Level {@code 0} holds the {@code N / 2^L} final approximation coefficients and
 * level {@code j} in {@code 1..L} the {@code N / 2^j} detail coefficients of that level, in strictly ascending
 * {@code (level, index)} order: approximation, then the finest details, then coarser ones. A level is a dyadic scale
 * (detail level {@code j} measures change across {@code 2^j} samples) and a locator is the sample position where the
 * coefficient's support starts ({@code m · 2^j}, {@code m · 2^L} for the approximation). Neither is a frequency and
 * neither is ever reported in hertz ({@link LocatorUnit#SAMPLE_POSITION}); a Haar pattern is never comparable bin by
 * bin with a DFT pattern, whose frame differs in basis, scale and locator unit.
 *
 * <p><b>Normalization.</b> Coefficients are the orthonormal values themselves: {@code magnitude = |c|},
 * {@code phase = 0} for {@code c >= 0} and {@code π} for {@code c < 0}. The transform preserves energy, so
 * {@code Σ c² = Σ x_n²} within the Parseval tolerance, and {@link SpectralPattern#levelEnergy(int)} gives the energy
 * of each scale.
 *
 * <p><b>Length policy.</b> {@code N} must be a power of two with {@code 2^L <= N}. Any other length is rejected with
 * {@link DecompositionRejectionReason#SAMPLE_COUNT_UNSUPPORTED}: never padded (padding would add boundary energy at
 * every scale that the samples do not contain), never truncated and never decomposed to a shallower depth. Choosing
 * window lengths is the host encoder's responsibility ({@link WaveEncoder}).
 *
 * <p><b>Determinism.</b> {@code √2} is {@code StrictMath.sqrt(2.0)}; sums and quotients run in ascending {@code m},
 * levels in ascending order, in {@code double}, single-threaded, so the same wave gives a bit-identical pattern on
 * every run and JVM.
 *
 * <p><b>Rejections.</b> {@link DecompositionRejectionReason#SAMPLE_COUNT_UNSUPPORTED} as above, and
 * {@link DecompositionRejectionReason#ENERGY_OUT_OF_RANGE} with the same rule as {@link DftWaveDecomposer}. An
 * accepted energy is at most {@link SpectralPattern#MAX_SOURCE_ENERGY}, so every {@code |x_n|} and every intermediate
 * coefficient is at most {@code √(Double.MAX_VALUE / 2)} and pairwise sums cannot overflow. The sample rate only
 * travels into the frame; Haar locators do not depend on it.
 *
 * <p><b>Cost.</b> O(N) time (about {@code 2N} additions and {@code 2N} divisions over all levels) and O(N) memory:
 * one working array of {@code N / 2} approximations, reused in place by every level, and the four output arrays of
 * {@code N} entries, which the pattern takes over without copying. No trigonometric call is made.
 */
public final class HaarWaveDecomposer implements WaveDecomposer {

    private static final double SQRT_2 = StrictMath.sqrt(2.0);

    private final SpectralScale scale;

    /**
     * Creates a decomposer of fixed depth.
     *
     * @param levels decomposition depth {@code L}, {@code 1..SpectralScale.MAX_HAAR_LEVELS}
     * @throws IllegalArgumentException if {@code levels} is outside that range
     */
    public HaarWaveDecomposer(int levels) {
        if (levels < 1 || levels > SpectralScale.MAX_HAAR_LEVELS) {
            throw new IllegalArgumentException(
                    "levels must be in [1, " + SpectralScale.MAX_HAAR_LEVELS + "], got: " + levels);
        }
        this.scale = new SpectralScale(levels);
    }

    @Override
    public SpectralBasis basis() {
        return SpectralBasis.HAAR;
    }

    /** Returns the fixed decomposition depth of every pattern this decomposer produces. */
    public SpectralScale scale() {
        return scale;
    }

    @Override
    public DecompositionResult decompose(SampledWave wave) {
        int n = wave.sampleCount();
        int depth = scale.levels();
        if (Integer.bitCount(n) != 1 || (n >> depth) == 0) {
            return new DecompositionRejected(DecompositionRejectionReason.SAMPLE_COUNT_UNSUPPORTED,
                    "Haar of depth " + depth + " needs a power-of-two sampleCount of at least " + (1 << depth)
                            + ", got: " + n);
        }
        double[] x = wave.sampleArray();
        double peak = SpectralArithmetic.peakMagnitude(x);
        double energy = SpectralArithmetic.sumOfSquares(x, peak);
        if (!SpectralArithmetic.sourceEnergyRepresentable(peak, energy)) {
            return new DecompositionRejected(DecompositionRejectionReason.ENERGY_OUT_OF_RANGE,
                    "source energy is outside [MIN_NORMAL, MAX_SOURCE_ENERGY]: " + energy);
        }

        var levels = new int[n];
        var indices = new int[n];
        var magnitudes = new double[n];
        var phases = new double[n];
        var approximation = new double[n / 2];

        double[] source = x;
        int offset = n >> depth;
        for (int level = 1; level <= depth; level++) {
            int count = n >> level;
            for (int m = 0; m < count; m++) {
                double even = source[2 * m];
                double odd = source[2 * m + 1];
                approximation[m] = (even + odd) / SQRT_2;
                setCoefficient(offset + m, level, m, (even - odd) / SQRT_2, levels, indices, magnitudes, phases);
            }
            source = approximation;
            offset += count;
        }
        for (int m = 0, count = n >> depth; m < count; m++) {
            setCoefficient(m, 0, m, approximation[m], levels, indices, magnitudes, phases);
        }

        var frame = new SpectralFrame(SpectralBasis.HAAR, scale, n, wave.sampleRate(), wave.unit());
        return new Decomposed(SpectralPattern.ofOwned(frame, energy, wave.provenance(), levels, indices, magnitudes,
                phases));
    }

    private void setCoefficient(int slot, int level, int index, double coefficient, int[] levels, int[] indices,
                                double[] magnitudes, double[] phases) {
        levels[slot] = level;
        indices[slot] = index;
        magnitudes[slot] = Math.abs(coefficient);
        phases[slot] = coefficient < 0.0 ? StrictMath.PI : 0.0;
    }
}
