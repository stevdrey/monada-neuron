package monada.neuron.spectral;

import monada.neuron.testsupport.AllocationProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static monada.neuron.spectral.SpectralFixtures.PROVENANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reports the modeled retained size and the measured construction allocation of {@link SpectralPattern}: both are
 * O(k) primitive storage with no per-component object, and indexed reads do not allocate.
 */
class SpectralPatternFootprintTest {

    private static final SpectralFrame FRAME = new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(12), 4096,
            4096.0, SampleUnit.HERTZ);

    private final SpectralPatternFootprintModel model = new SpectralPatternFootprintModel();
    private SpectralPattern sink;
    private double readSink;

    @Test
    void modelsExactBytesForKnownSizes() {
        // pattern 64; 2 * int[0] 16; 2 * double[0] 16
        assertEquals(64 + 32 + 32, model.retainedBytes(0));
        // pattern 64; 2 * int[8] 48; 2 * double[8] 80
        assertEquals(64 + 96 + 160, model.retainedBytes(8));
        // 24 bytes per component plus fixed overhead
        assertEquals(64 + 2 * 16400 + 2 * 32784, model.retainedBytes(4096));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 8, 129, 2049, 4096})
    void constructionAllocatesOnlyTheModeledArrays(int k) {
        var levels = new int[k];
        var indices = new int[k];
        fillKeys(levels, indices);
        var magnitudes = new double[k];
        var phases = new double[k];
        Arrays.fill(magnitudes, 1.0);

        long measured = AllocationProbe.measure(2_000,
                () -> sink = SpectralPattern.of(FRAME, k, PROVENANCE, levels, indices, magnitudes, phases));
        long modeled = model.retainedBytes(k);

        System.out.printf("SpectralPattern k=%d modeled=%d B measured=%d B%n", k, modeled, measured);
        assertTrue(measured <= modeled + 64, "k=" + k + " measured " + measured + " B, modeled " + modeled + " B");
        assertEquals(k, sink.componentCount());
    }

    @Test
    void rewrappingAComponentViewAllocatesOnlyThePattern() {
        var source = SpectralFixtures.p9();
        var components = source.components();

        long measured = AllocationProbe.measure(2_000,
                () -> sink = SpectralPattern.of(SpectralFixtures.HAAR_4_L2, 70.0, PROVENANCE, components));

        assertTrue(measured <= model.retainedBytes(0) - 4 * 16, "measured " + measured + " B");
    }

    @Test
    void hashCodeDoesNotAllocate() {
        var pattern = SpectralFixtures.p9();

        long measured = AllocationProbe.measure(20_000, () -> readSink = pattern.hashCode());

        assertEquals(0L, measured);
    }

    @Test
    void indexedReadsDoNotAllocate() {
        var pattern = SpectralFixtures.p9();

        long measured = AllocationProbe.measure(20_000, () -> {
            double sum = 0.0;
            for (int i = 0; i < pattern.componentCount(); i++) {
                sum += pattern.magnitude(i) * pattern.phase(i) + pattern.locator(i) + (pattern.phaseDefined(i) ? 1 : 0);
            }
            readSink = sum;
        });

        assertEquals(0L, measured);
    }

    private static void fillKeys(int[] levels, int[] indices) {
        int i = 0;
        for (int level = 0; level <= FRAME.scale().levels() && i < levels.length; level++) {
            for (int index = 0; FRAME.containsKey(level, index) && i < levels.length; index++) {
                levels[i] = level;
                indices[i] = index;
                i++;
            }
        }
    }
}
