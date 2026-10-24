package monada.neuron.spectral;

import org.junit.jupiter.api.Test;

import static monada.neuron.spectral.SpectralFixtures.PROVENANCE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract section 4.1 and negative fixtures N1, N2, N3 and N5. */
class SampledWaveTest {

    @Test
    void keepsSamplesSamplingMetadataAndProvenance() {
        var wave = new SampledWave(new double[] {1.0, -2.0, 3.5}, 8.0, SampleUnit.HERTZ, PROVENANCE);

        assertAll(
                () -> assertArrayEquals(new double[] {1.0, -2.0, 3.5}, wave.samples()),
                () -> assertEquals(3, wave.sampleCount()),
                () -> assertEquals(-2.0, wave.sample(1)),
                () -> assertEquals(8.0, wave.sampleRate()),
                () -> assertEquals(SampleUnit.HERTZ, wave.unit()),
                () -> assertEquals(PROVENANCE, wave.provenance()));
    }

    @Test
    void copiesTheSamplesOnConstructionAndAccess() {
        var source = new double[] {1.0, 2.0};
        var wave = new SampledWave(source, 1.0, SampleUnit.ABSTRACT_PER_INDEX, PROVENANCE);
        source[0] = 99.0;
        wave.samples()[1] = 99.0;

        assertArrayEquals(new double[] {1.0, 2.0}, wave.samples());
    }

    @Test
    void isAValueOverItsSamples() {
        var first = new SampledWave(new double[] {1.0, 0.0}, 8.0, SampleUnit.HERTZ, PROVENANCE);
        var second = new SampledWave(new double[] {1.0, -0.0}, 8.0, SampleUnit.HERTZ, PROVENANCE);
        var other = new SampledWave(new double[] {1.0, 0.5}, 8.0, SampleUnit.HERTZ, PROVENANCE);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(second.sample(1)));
        assertNotEquals(first, other);
        assertNotEquals(first, new SampledWave(new double[] {1.0, 0.0}, 16.0, SampleUnit.HERTZ, PROVENANCE));
        assertTrue(first.toString().contains("sampleCount=2"));
    }

    @Test
    void acceptsTheBoundaryLengths() {
        assertEquals(1, new SampledWave(new double[1], 1.0, SampleUnit.HERTZ, PROVENANCE).sampleCount());
        assertEquals(SampledWave.MAX_SAMPLES,
                new SampledWave(new double[SampledWave.MAX_SAMPLES], 1.0, SampleUnit.HERTZ, PROVENANCE)
                        .sampleCount());
    }

    @Test
    void rejectsEmptyAndOversizedSampleArrays() {
        assertThrows(IllegalArgumentException.class,
                () -> new SampledWave(new double[0], 8.0, SampleUnit.HERTZ, PROVENANCE));
        assertThrows(IllegalArgumentException.class,
                () -> new SampledWave(new double[SampledWave.MAX_SAMPLES + 1], 8.0, SampleUnit.HERTZ, PROVENANCE));
    }

    @Test
    void rejectsNonFiniteSamples() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new SampledWave(new double[] {0.0, bad}, 8.0, SampleUnit.HERTZ, PROVENANCE));
            assertTrue(error.getMessage().contains("index 1"));
        }
    }

    @Test
    void rejectsInvalidSampleRatesAndUnits() {
        for (double bad : new double[] {0.0, -1.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SampledWave(new double[] {1.0}, bad, SampleUnit.HERTZ, PROVENANCE));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SampledWave(new double[] {1.0}, 8.0, SampleUnit.ABSTRACT_PER_INDEX, PROVENANCE));
        assertThrows(NullPointerException.class,
                () -> new SampledWave(new double[] {1.0}, 8.0, null, PROVENANCE));
    }

    @Test
    void rejectsNullsAndOffsetOverflow() {
        assertThrows(NullPointerException.class, () -> new SampledWave(null, 8.0, SampleUnit.HERTZ, PROVENANCE));
        assertThrows(NullPointerException.class,
                () -> new SampledWave(new double[] {1.0}, 8.0, SampleUnit.HERTZ, null));
        var lastOffset = new WaveProvenance("encoder.fixture", 1, "window-0", Long.MAX_VALUE - 1);
        assertEquals(1, new SampledWave(new double[1], 8.0, SampleUnit.HERTZ, lastOffset).sampleCount());
        assertThrows(IllegalArgumentException.class,
                () -> new SampledWave(new double[2], 8.0, SampleUnit.HERTZ, lastOffset));
    }

    @Test
    void sampleReadsAreBoundsChecked() {
        var wave = new SampledWave(new double[] {1.0}, 8.0, SampleUnit.HERTZ, PROVENANCE);

        assertThrows(IndexOutOfBoundsException.class, () -> wave.sample(1));
        assertThrows(IndexOutOfBoundsException.class, () -> wave.sample(-1));
    }
}
