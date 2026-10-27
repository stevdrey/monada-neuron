package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;
import monada.neuron.spectral.SpectralPattern;
import org.junit.jupiter.api.Test;

import static monada.neuron.resonance.ResonanceFixtures.DFT_8;
import static monada.neuron.resonance.ResonanceFixtures.PI;
import static monada.neuron.resonance.ResonanceFixtures.PROVENANCE;
import static monada.neuron.resonance.ResonanceFixtures.p4;
import static monada.neuron.resonance.ResonanceFixtures.p9;
import static monada.neuron.resonance.ResonanceFixtures.sparse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The lossy dominant-component projection used as the H2 scalar control (contract section 14.2). */
class DominantComponentProjectionTest {

    private final DominantComponentProjection projection = new DominantComponentProjection();

    @Test
    void projectsTheLargestComponentWithItsFrequencyAndPhase() {
        assertEquals(new FrequencyState(1.0, 1.0, 0.0), projection.project(p4()));
        assertEquals(new FrequencyState(0.5, 3.0, PI / 4), projection.project(
                sparse(new int[] {1, 3}, new double[] {0.25, 0.5}, new double[] {0.0, PI / 4})));
    }

    @Test
    void breaksTiesTowardsTheLowerBin() {
        assertEquals(new FrequencyState(1.0, 1.0, 0.5), projection.project(
                sparse(new int[] {1, 3}, new double[] {1.0, 1.0}, new double[] {0.5, -0.5})));
    }

    @Test
    void silentOrEmptyPatternsProjectToZero() {
        var silent = SpectralPattern.of(DFT_8, 0.0, PROVENANCE, new int[5], new int[] {0, 1, 2, 3, 4},
                new double[5], new double[5]);

        assertEquals(FrequencyState.ZERO, projection.project(silent));
        assertEquals(FrequencyState.ZERO, projection.project(sparse(new int[0], new double[0], new double[0])));
    }

    @Test
    void rejectsHaarPatternsWhoseLocatorsArePositions() {
        assertThrows(IllegalArgumentException.class, () -> projection.project(p9()));
        assertThrows(NullPointerException.class, () -> projection.project(null));
    }
}
