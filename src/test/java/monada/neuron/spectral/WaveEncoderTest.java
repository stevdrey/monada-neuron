package monada.neuron.spectral;

import monada.neuron.spectral.DecompositionResult.Decomposed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The host-encoder seam: a host-owned encoder supplies the samples and provenance; Neuron only decomposes. */
class WaveEncoderTest {

    /** Synthetic host source with a documented formula: {@code amplitude · cos(2π · bin · n / N + phase)}. */
    private record Tone(double amplitude, int bin, double phase, int sampleCount) {
    }

    private static final WaveEncoder<Tone> TONE_ENCODER = tone -> {
        var samples = new double[tone.sampleCount()];
        for (int n = 0; n < samples.length; n++) {
            samples[n] = tone.amplitude()
                    * StrictMath.cos(2.0 * StrictMath.PI * tone.bin() * n / tone.sampleCount() + tone.phase());
        }
        return new SampledWave(samples, tone.sampleCount(), SampleUnit.HERTZ,
                new WaveProvenance("encoder.tone", 2, "tone-" + tone.bin(), 64L));
    };

    @Test
    void decomposesWhatTheHostEncoderDeclares() {
        var wave = TONE_ENCODER.encode(new Tone(2.5, 3, StrictMath.PI / 6, 16));
        var pattern = assertInstanceOf(Decomposed.class, new DftWaveDecomposer().decompose(wave)).pattern();

        assertEquals(new WaveProvenance("encoder.tone", 2, "tone-3", 64L), pattern.provenance());
        assertEquals(3.0, pattern.locator(3));
        assertEquals(2.5, pattern.magnitude(3), 1e-12);
        assertEquals(StrictMath.PI / 6, pattern.phase(3), 1e-12);
    }
}
