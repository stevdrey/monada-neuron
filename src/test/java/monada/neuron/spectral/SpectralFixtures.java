package monada.neuron.spectral;

/** Shared frames and provenance for the spectral contract tests (contract section 12: N = 8, 8 Hz). */
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
}
