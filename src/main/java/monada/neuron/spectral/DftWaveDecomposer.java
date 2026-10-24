package monada.neuron.spectral;

import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.DecompositionResult.DecompositionRejected;

/**
 * Scalar reference DFT decomposer (contract {@code ncm-wave-cognition/1} sections 4.2, 5.2 and 6, ADR 0036).
 *
 * <p><b>Definition.</b> {@code X_k = Σ_{n=0}^{N-1} x_n · e^{-2πi·k·n/N}} for the single-sided bins
 * {@code k = 0..floor(N/2)}, any {@code N} in {@code 1..SampledWave.MAX_SAMPLES} (no power-of-two requirement, no
 * padding, rectangular window). Bin {@code k} locates at {@code k · sampleRate / N}: hertz for
 * {@link SampleUnit#HERTZ}, cycles per index for {@link SampleUnit#ABSTRACT_PER_INDEX}. The time origin is the first
 * sample of the window ({@code t_n = n / sampleRate}), so phases are relative to it.
 *
 * <p><b>Normalization.</b> Magnitude {@code |X_k| / N} for the DC bin and, for even {@code N}, the Nyquist bin
 * {@code k = N/2}; {@code 2·|X_k| / N} otherwise, so a cosine of amplitude {@code A} at an integer bin reports
 * {@code A}. Negative frequencies are not reported: for real input {@code X_{N-k}} is the conjugate of {@code X_k},
 * and the factor {@code 2} accounts for it. The DC and Nyquist bins are computed as real sums ({@code Σ x_n} and
 * {@code Σ (-1)^n x_n}), so their phase is exactly {@code 0} or {@code π} by sign. Every other phase is
 * {@code atan2(Im, Re)} wrapped by {@link SpectralPhase#wrap(double)} and stored whatever the magnitude.
 *
 * <p><b>Determinism.</b> Every trigonometric value is {@code StrictMath.cos} or {@code StrictMath.sin} of
 * {@code (2π · m) / N} with {@code m = (k·n) mod N}, read from a table of {@code N} entries built per call; the residue
 * is tracked exactly in integer arithmetic. Sums run in ascending {@code n}, bins in ascending {@code k}, in
 * {@code double}, single-threaded. The same wave therefore gives a bit-identical pattern on every run and JVM.
 *
 * <p><b>Aliasing.</b> Content above the Nyquist limit {@code sampleRate / 2} folds onto a lower bin and cannot be
 * detected from the samples; it is not an error and no heuristic flags it. Pre-filtering is the host encoder's
 * responsibility ({@link WaveEncoder}).
 *
 * <p><b>Rejections.</b> {@link DecompositionRejectionReason#SAMPLE_RATE_OUT_OF_RANGE} when the DFT frame cannot locate
 * the bins, and {@link DecompositionRejectionReason#ENERGY_OUT_OF_RANGE} when {@code Σ x_n²} overflows or is nonzero
 * but below {@link Double#MIN_NORMAL}. The source energy is summed with samples scaled by a power of two, as
 * {@link SpectralPattern#retainedEnergy()} is, so a representable energy is never lost to squaring underflow.
 *
 * <p><b>Cost.</b> O(N²) time (about {@code N²/2} multiply-adds per window, 8.4 M at {@code N = 4096}), O(N) memory:
 * one sample copy, two twiddle tables and the four output arrays. This is a correctness reference, not a fast
 * transform; a later FFT backend must match it within a documented tolerance.
 */
public final class DftWaveDecomposer implements WaveDecomposer {

    private static final double TWO_PI = 2.0 * StrictMath.PI;

    /** Creates the stateless reference decomposer. */
    public DftWaveDecomposer() {
    }

    @Override
    public SpectralBasis basis() {
        return SpectralBasis.DFT;
    }

    @Override
    public DecompositionResult decompose(SampledWave wave) {
        int n = wave.sampleCount();
        double rate = wave.sampleRate();
        if (!SpectralFrame.dftHighestLocatorFinite(n, rate)) {
            return new DecompositionRejected(DecompositionRejectionReason.SAMPLE_RATE_OUT_OF_RANGE,
                    "highest DFT locator overflows: sampleCount=" + n + ", sampleRate=" + rate);
        }
        if (!SpectralFrame.dftBinSpacingNormal(n, rate)) {
            return new DecompositionRejected(DecompositionRejectionReason.SAMPLE_RATE_OUT_OF_RANGE,
                    "DFT bin spacing is subnormal: sampleCount=" + n + ", sampleRate=" + rate);
        }
        double[] x = wave.samples();
        double peak = peakMagnitude(x);
        double energy = sourceEnergy(x, peak);
        if (peak != 0.0 && !(Double.isFinite(energy) && energy >= Double.MIN_NORMAL)) {
            return new DecompositionRejected(DecompositionRejectionReason.ENERGY_OUT_OF_RANGE,
                    "source energy is not a normal finite double: " + energy);
        }

        int binCount = n / 2 + 1;
        int nyquist = n % 2 == 0 ? n / 2 : -1;
        var levels = new int[binCount];
        var indices = new int[binCount];
        var magnitudes = new double[binCount];
        var phases = new double[binCount];
        for (int k = 0; k < binCount; k++) {
            indices[k] = k;
        }

        double dc = 0.0;
        for (int i = 0; i < n; i++) {
            dc += x[i];
        }
        setRealBin(0, dc, n, magnitudes, phases);
        if (nyquist > 0) {
            double alternating = 0.0;
            for (int i = 0; i < n; i++) {
                alternating += (i & 1) == 0 ? x[i] : -x[i];
            }
            setRealBin(nyquist, alternating, n, magnitudes, phases);
        }

        int lastComplex = nyquist > 0 ? nyquist - 1 : binCount - 1;
        if (lastComplex >= 1) {
            var cos = new double[n];
            var sin = new double[n];
            for (int m = 0; m < n; m++) {
                double angle = (TWO_PI * m) / n;
                cos[m] = StrictMath.cos(angle);
                sin[m] = StrictMath.sin(angle);
            }
            for (int k = 1; k <= lastComplex; k++) {
                double re = 0.0;
                double im = 0.0;
                int m = 0;
                for (int i = 0; i < n; i++) {
                    re += x[i] * cos[m];
                    im -= x[i] * sin[m];
                    m += k;
                    if (m >= n) {
                        m -= n;
                    }
                }
                magnitudes[k] = 2.0 * StrictMath.hypot(re, im) / n;
                phases[k] = SpectralPhase.wrap(StrictMath.atan2(im, re));
            }
        }

        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, n, rate, wave.unit());
        return new Decomposed(SpectralPattern.of(frame, energy, wave.provenance(), levels, indices, magnitudes,
                phases));
    }

    private void setRealBin(int k, double sum, int n, double[] magnitudes, double[] phases) {
        magnitudes[k] = Math.abs(sum) / n;
        phases[k] = sum < 0.0 ? StrictMath.PI : 0.0;
    }

    private double peakMagnitude(double[] x) {
        double peak = 0.0;
        for (double sample : x) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    /**
     * {@code Σ x_n²} in ascending order, with samples scaled by {@code 2^-e}, {@code e} the exponent of the peak.
     * Nonzero samples whose energy underflows return a value below {@link Double#MIN_NORMAL}, possibly {@code 0}.
     */
    private double sourceEnergy(double[] x, double peak) {
        if (peak == 0.0) {
            return 0.0;
        }
        int exponent = Math.getExponent(peak);
        double sum = 0.0;
        for (double sample : x) {
            double scaled = Math.scalb(sample, -exponent);
            sum += scaled * scaled;
        }
        return Math.scalb(sum, 2 * exponent);
    }
}
