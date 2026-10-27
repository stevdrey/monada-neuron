package monada.neuron.spectral;

import java.util.Objects;
import java.util.OptionalDouble;

import monada.neuron.spectral.SynthesisResult.NotInvertible;
import monada.neuron.spectral.SynthesisResult.Reconstructed;

/**
 * Scalar reference synthesizer: the inverse of {@link DftWaveDecomposer} and {@link HaarWaveDecomposer} with explicit
 * reconstruction fidelity (contract {@code ncm-wave-cognition/1} section 9, ADR 0042).
 *
 * <p><b>Status.</b> Decided structurally, never from a measured error. A complete pattern (every key of its frame
 * present) is {@link SynthesisStatus#EXACT}. A pattern with dropped components (top-K, thresholding) is
 * {@link SynthesisStatus#LOSSY}: each absent component is synthesized as zero, which is exactly what dropping it
 * meant. A Haar pattern missing any coefficient of its final approximation (level {@code 0}) is
 * {@link SynthesisResult.NotInvertible} with {@link NotInvertibleReason#MISSING_APPROXIMATION}. A DFT pattern is
 * always invertible; a missing DC bin is lossy like any other bin.
 *
 * <p><b>Inverse DFT.</b> Single-sided and conjugate-symmetric for real samples, {@code N} and the time origin (first
 * sample) from the frame, rectangular window:
 * {@code x̂_n = c_0 + Σ_{0<k<N/2} m_k · cos(2π·k·n/N + φ_k) + c_{N/2} · (−1)^n}, where {@code c} is the signed value
 * of a real bin ({@code −m} for phase {@code π}) and the Nyquist term exists only for even {@code N}. The factor
 * {@code 2} of the conjugate pair is already part of the normalized magnitude (contract section 6). Complex bins use
 * {@code m · StrictMath.cos(φ)} and {@code m · StrictMath.sin(φ)} with the twiddle table and exact integer residue
 * {@code (k·n) mod N} of {@link DftWaveDecomposer}. Every stored phase is used, including those whose
 * {@link SpectralPattern#phaseDefined(int)} is {@code false}. Cost O(N·k) for {@code k} retained bins, at most
 * O(N²), and O(N) memory.
 *
 * <p><b>Inverse Haar.</b> From the coarsest level to the finest, {@code x_{2m} = (a_m + d_m) / √2} and
 * {@code x_{2m+1} = (a_m − d_m) / √2} with {@code √2 = StrictMath.sqrt(2.0)}, the coefficient sign read from its
 * phase. Each level runs in descending {@code m} in place in the output array. Cost O(N) time and memory (the output
 * and one dense coefficient array).
 *
 * <p><b>Fidelity.</b> Every result reports the retained-energy fraction ({@link SpectralPattern#retainedEnergy()} over
 * {@link SpectralPattern#sourceEnergy()}, {@code 1} for silence and exactly {@code 1} for a complete pattern, whose
 * energy the pattern already validated within the Parseval allowance), computable from the pattern alone, and every
 * reconstruction its energy {@code Σ x̂_n²}. The original must match the pattern's silence exactly (both energies
 * {@code 0}, or neither). Errors against the original samples ({@code max |x̂ − x|}, RMS and
 * relative energy error) are reported only by {@link #synthesize(SpectralPattern, SampledWave)}; they are never
 * estimated. Energies and RMS are summed with the power-of-two scaling of {@link SpectralArithmetic}, so they stay
 * finite for every valid pattern.
 *
 * <p><b>Determinism.</b> {@code StrictMath}, fixed summation order (components ascending, samples ascending inside),
 * {@code double}, single-threaded: the same pattern gives bit-identical samples on every run and JVM. The class is
 * stateless and thread-safe. Synthesis reconstructs samples; it does not generate images, audio, text or concept
 * semantics.
 */
public final class WaveSynthesizer {

    private static final double SQRT_2 = StrictMath.sqrt(2.0);

    /** Creates the stateless reference synthesizer. */
    public WaveSynthesizer() {
    }

    /**
     * Synthesizes the samples of a pattern without error diagnostics.
     *
     * @param pattern the pattern to invert
     * @return the reconstruction, with empty error diagnostics, or a typed refusal
     */
    public SynthesisResult synthesize(SpectralPattern pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        return reconstruct(pattern, null);
    }

    /**
     * Synthesizes the samples of a pattern and measures them against the original samples.
     *
     * @param pattern the pattern to invert
     * @param original the decomposed wave; its sample count, sample rate, unit and provenance must equal the
     *                 pattern's, and its energy {@code Σ x_n²} the pattern's source energy within the Parseval
     *                 tolerance
     * @return the reconstruction with error diagnostics, or a typed refusal
     * @throws IllegalArgumentException if {@code original} is not the wave the pattern describes
     */
    public SynthesisResult synthesize(SpectralPattern pattern, SampledWave original) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        Objects.requireNonNull(original, "original must not be null");
        requireOriginalOf(pattern, original);
        return reconstruct(pattern, original);
    }

    private SynthesisResult reconstruct(SpectralPattern pattern, SampledWave original) {
        boolean complete = pattern.componentCount() == pattern.frame().componentCapacity();
        double fraction = complete ? 1.0 : retainedEnergyFraction(pattern);
        if (pattern.basis() == SpectralBasis.HAAR) {
            int approximationCount = pattern.sampleCount() >> pattern.scale().levels();
            int present = leadingApproximationCount(pattern);
            if (present < approximationCount) {
                return new NotInvertible(NotInvertibleReason.MISSING_APPROXIMATION, fraction,
                        "Haar final approximation has " + present + " of " + approximationCount + " coefficients");
            }
        }
        double[] samples = switch (pattern.basis()) {
            case DFT -> inverseDft(pattern);
            case HAAR -> inverseHaar(pattern);
        };
        var status = complete ? SynthesisStatus.EXACT : SynthesisStatus.LOSSY;
        double energy = SpectralArithmetic.sumOfSquares(samples, SpectralArithmetic.peakMagnitude(samples));
        if (original == null) {
            return Reconstructed.ofOwned(status, samples, fraction, energy, OptionalDouble.empty(),
                    OptionalDouble.empty(), OptionalDouble.empty());
        }
        double[] x = original.sampleArray();
        double maxError = SpectralArithmetic.peakDifference(samples, x);
        // Rounding of the mean can lift the RMS an ulp above the peak; mathematically it never exceeds it.
        double rmsError = Math.min(maxError, SpectralArithmetic.rootMeanSquareDifference(samples, x, maxError));
        // requireOriginalOf admits a silent original only for a silent pattern, whose reconstruction is silent too.
        double relativeEnergyError = SpectralArithmetic.relativeEnergyDifference(samples, x);
        return Reconstructed.ofOwned(status, samples, fraction, energy, OptionalDouble.of(maxError),
                OptionalDouble.of(rmsError), OptionalDouble.of(relativeEnergyError));
    }

    /**
     * Requires the original to be the wave the pattern was decomposed from, as far as the pattern can tell. Silence
     * must match exactly, as in {@link SpectralPattern}: an original whose samples are all {@code 0} only for a pattern
     * of source energy {@code 0}, and the reverse. Silence is judged by the samples, not by their energy, which can
     * underflow to {@code 0} for subnormal samples.
     */
    private void requireOriginalOf(SpectralPattern pattern, SampledWave original) {
        if (original.sampleCount() != pattern.sampleCount()
                || Double.compare(original.sampleRate(), pattern.sampleRate()) != 0
                || original.unit() != pattern.unit()) {
            throw new IllegalArgumentException("original " + original + " does not match the frame "
                    + pattern.frame());
        }
        if (!original.provenance().equals(pattern.provenance())) {
            throw new IllegalArgumentException("original provenance " + original.provenance()
                    + " does not match the pattern provenance " + pattern.provenance());
        }
        double[] x = original.sampleArray();
        double peak = SpectralArithmetic.peakMagnitude(x);
        if ((peak == 0.0) != (pattern.sourceEnergy() == 0.0)) {
            throw new IllegalArgumentException("original peak " + peak + " and pattern sourceEnergy "
                    + pattern.sourceEnergy() + " must both be 0 or both be nonzero");
        }
        double energy = SpectralArithmetic.sumOfSquares(x, peak);
        double scale = Math.max(energy, pattern.sourceEnergy());
        double allowed = SpectralArithmetic.energyAllowance(scale,
                pattern.sampleCount() + pattern.componentCount() + 4.0);
        if (Math.abs(energy - pattern.sourceEnergy()) > allowed) {
            throw new IllegalArgumentException("original energy " + energy + " does not match the pattern "
                    + "sourceEnergy " + pattern.sourceEnergy() + " within " + allowed);
        }
    }

    /** Returns {@code min(1, retained / source)} for an incomplete pattern, {@code 1} when the source energy is 0. */
    private double retainedEnergyFraction(SpectralPattern pattern) {
        double source = pattern.sourceEnergy();
        return source == 0.0 ? 1.0 : Math.min(1.0, pattern.retainedEnergy() / source);
    }

    /** Returns the number of leading level-0 components; keys are unique and sorted, so these are the first ones. */
    private int leadingApproximationCount(SpectralPattern pattern) {
        int count = 0;
        while (count < pattern.componentCount() && pattern.level(count) == 0) {
            count++;
        }
        return count;
    }

    private double[] inverseDft(SpectralPattern pattern) {
        int n = pattern.sampleCount();
        int nyquist = n % 2 == 0 ? n / 2 : -1;
        var samples = new double[n];
        double[] cos = null;
        double[] sin = null;
        for (int c = 0; c < pattern.componentCount(); c++) {
            int k = pattern.index(c);
            double magnitude = pattern.magnitude(c);
            double phase = pattern.phase(c);
            if (k == 0) {
                double value = signed(magnitude, phase);
                for (int i = 0; i < n; i++) {
                    samples[i] += value;
                }
            } else if (k == nyquist) {
                double value = signed(magnitude, phase);
                for (int i = 0; i < n; i++) {
                    samples[i] += (i & 1) == 0 ? value : -value;
                }
            } else {
                if (cos == null) {
                    cos = new double[n];
                    sin = new double[n];
                    SpectralArithmetic.fillTwiddles(cos, sin);
                }
                double re = magnitude * StrictMath.cos(phase);
                double im = magnitude * StrictMath.sin(phase);
                int m = 0;
                for (int i = 0; i < n; i++) {
                    samples[i] += re * cos[m] - im * sin[m];
                    m += k;
                    if (m >= n) {
                        m -= n;
                    }
                }
            }
        }
        return samples;
    }

    private double[] inverseHaar(SpectralPattern pattern) {
        int n = pattern.sampleCount();
        int depth = pattern.scale().levels();
        // Dense coefficients in canonical layout: level 0 at 0, level j >= 1 after the coarser offsets.
        var offsets = new int[depth + 1];
        offsets[1] = n >> depth;
        for (int level = 1; level < depth; level++) {
            offsets[level + 1] = offsets[level] + (n >> level);
        }
        var coefficients = new double[n];
        for (int c = 0; c < pattern.componentCount(); c++) {
            coefficients[offsets[pattern.level(c)] + pattern.index(c)] = signed(pattern.magnitude(c),
                    pattern.phase(c));
        }
        var samples = new double[n];
        System.arraycopy(coefficients, 0, samples, 0, n >> depth);
        for (int level = depth; level >= 1; level--) {
            int offset = offsets[level];
            // Descending m writes 2m and 2m + 1, never an approximation not yet read.
            for (int m = (n >> level) - 1; m >= 0; m--) {
                double a = samples[m];
                double d = coefficients[offset + m];
                samples[2 * m] = (a + d) / SQRT_2;
                samples[2 * m + 1] = (a - d) / SQRT_2;
            }
        }
        return samples;
    }

    /** Returns the signed value of a real-valued component: {@code −magnitude} for phase {@code π}. */
    private double signed(double magnitude, double phase) {
        return phase == StrictMath.PI ? -magnitude : magnitude;
    }
}
