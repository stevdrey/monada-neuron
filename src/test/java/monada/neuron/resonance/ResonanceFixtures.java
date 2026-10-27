package monada.neuron.resonance;

import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralScale;
import monada.neuron.spectral.WaveProvenance;

/** Spectral patterns of contract section 12 (N = 8, 8 Hz, so bin k locates at k Hz) for the resonance tests. */
final class ResonanceFixtures {

    static final WaveProvenance PROVENANCE = new WaveProvenance("encoder.fixture", 1, "window-0", 0L);
    static final SpectralFrame DFT_8 = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 8.0,
            SampleUnit.HERTZ);
    static final SpectralFrame HAAR_4_L2 = new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(2), 4, 4.0,
            SampleUnit.HERTZ);
    static final double PI = StrictMath.PI;

    private ResonanceFixtures() {
    }

    /** P2 shape: {@code cos(2π·1·t + phase)}, a complete pattern with bin 1 magnitude 1. */
    static SpectralPattern p2(double phase) {
        return SpectralPattern.of(DFT_8, 4.0, PROVENANCE,
                new int[] {0, 0, 0, 0, 0},
                new int[] {0, 1, 2, 3, 4},
                new double[] {0.0, 1.0, 0.0, 0.0, 0.0},
                new double[] {0.0, phase, 0.0, 0.0, 0.0});
    }

    /** P4 shape: bin 1 magnitude 1 phase 0, bin 3 magnitude 0.5 phase π/4, every other bin 0. */
    static SpectralPattern p4() {
        return SpectralPattern.of(DFT_8, 4.0 * 1.25, PROVENANCE,
                new int[] {0, 0, 0, 0, 0},
                new int[] {0, 1, 2, 3, 4},
                new double[] {0.0, 1.0, 0.0, 0.5, 0.0},
                new double[] {0.0, 0.0, 0.0, PI / 4, 0.0});
    }

    /** P4 with bin 3 dropped (a truncated pattern keeps the source energy of P4). */
    static SpectralPattern p4WithoutBin3() {
        return SpectralPattern.of(DFT_8, 4.0 * 1.25, PROVENANCE,
                new int[] {0, 0, 0, 0},
                new int[] {0, 1, 2, 4},
                new double[] {0.0, 1.0, 0.0, 0.0},
                new double[] {0.0, 0.0, 0.0, 0.0});
    }

    /** P9 shape: Haar of [4, 2, 5, 5] with 2 levels; approximation [8], level 2 [-2], level 1 [√2, 0]. */
    static SpectralPattern p9() {
        return SpectralPattern.of(HAAR_4_L2, 70.0, PROVENANCE,
                new int[] {0, 1, 1, 2},
                new int[] {0, 0, 1, 0},
                new double[] {8.0, StrictMath.sqrt(2.0), 0.0, 2.0},
                new double[] {0.0, 0.0, 0.0, PI});
    }

    /** An incomplete pattern of {@code frame} with the given level-0 bins; the source energy bounds what it keeps. */
    static SpectralPattern sparse(SpectralFrame frame, double sourceEnergy, int[] bins, double[] magnitudes,
                                  double[] phases) {
        return SpectralPattern.of(frame, sourceEnergy, PROVENANCE, new int[bins.length], bins, magnitudes, phases);
    }

    /** An incomplete N = 8 DFT pattern with a generous source energy. */
    static SpectralPattern sparse(int[] bins, double[] magnitudes, double[] phases) {
        return sparse(DFT_8, 1_000.0, bins, magnitudes, phases);
    }
}
