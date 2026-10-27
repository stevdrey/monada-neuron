package monada.neuron.spectral;

import java.util.function.IntToDoubleFunction;

/** Shared frames, provenance and wave helpers for the spectral contract tests (contract section 12: N = 8, 8 Hz). */
final class SpectralFixtures {

    static final WaveProvenance PROVENANCE = new WaveProvenance("encoder.fixture", 1, "window-0", 0L);
    static final SpectralFrame DFT_8 = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 8.0,
            SampleUnit.HERTZ);
    static final SpectralFrame HAAR_4_L2 = new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(2), 4, 4.0,
            SampleUnit.HERTZ);

    private SpectralFixtures() {
    }

    /** P4 shape: bin 1 magnitude 1 phase 0, bin 3 magnitude 0.5 phase π/4, every other bin 0. */
    static SpectralPattern p4() {
        return SpectralPattern.of(DFT_8, 4.0 * 1.25, PROVENANCE,
                new int[] {0, 0, 0, 0, 0},
                new int[] {0, 1, 2, 3, 4},
                new double[] {0.0, 1.0, 0.0, 0.5, 0.0},
                new double[] {0.0, 0.0, 0.0, StrictMath.PI / 4, 0.0});
    }

    /** P9 shape: Haar of [4, 2, 5, 5] with 2 levels; approximation [8], level 2 [-2], level 1 [√2, 0]. */
    static SpectralPattern p9() {
        return SpectralPattern.of(HAAR_4_L2, 70.0, PROVENANCE,
                new int[] {0, 1, 1, 2},
                new int[] {0, 0, 1, 0},
                new double[] {8.0, StrictMath.sqrt(2.0), 0.0, 2.0},
                new double[] {0.0, 0.0, 0.0, StrictMath.PI});
    }

    /** P4 samples: {@code cos(2π·1·t) + 0.5·cos(2π·3·t + π/4)} at {@code N = 8}, 8 Hz. */
    static SampledWave p4Wave() {
        return wave8(n -> StrictMath.cos(2 * StrictMath.PI * n / 8.0)
                + 0.5 * StrictMath.cos(2 * StrictMath.PI * 3 * n / 8.0 + StrictMath.PI / 4));
    }

    /** A wave of {@code N = 8} samples at 8 Hz, unit {@code HERTZ}, from {@code formula(n)}. */
    static SampledWave wave8(IntToDoubleFunction formula) {
        return new SampledWave(samples(8, formula), 8.0, SampleUnit.HERTZ, PROVENANCE);
    }

    /**
     * Seeded samples in {@code [-10, 10)} at {@code sampleRate = N} Hz from a fixed 64-bit LCG (Knuth's MMIX constants)
     * and explicit arithmetic, so the golden fixtures depend on no JDK random-number implementation.
     */
    static SampledWave randomWave(int n, long seed) {
        var samples = new double[n];
        long state = seed;
        for (int i = 0; i < n; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            samples[i] = -10.0 + 20.0 * ((state >>> 11) * 0x1.0p-53);
        }
        return new SampledWave(samples, n, SampleUnit.HERTZ, PROVENANCE);
    }

    /** Returns {@code formula(0..n-1)}. */
    static double[] samples(int n, IntToDoubleFunction formula) {
        var samples = new double[n];
        for (int i = 0; i < n; i++) {
            samples[i] = formula.applyAsDouble(i);
        }
        return samples;
    }

    /** Returns the raw bits of each value, for bit-exact comparisons. */
    static long[] bits(double[] values) {
        var bits = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Double.doubleToRawLongBits(values[i]);
        }
        return bits;
    }
}
