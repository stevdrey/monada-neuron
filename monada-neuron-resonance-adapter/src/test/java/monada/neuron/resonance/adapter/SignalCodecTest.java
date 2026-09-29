package monada.neuron.resonance.adapter;

import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalCodecTest {

    @Test
    void canonicalEncoderIsDeterministicAndDistinguishesSignals() {
        var encoder = new CanonicalSignalQueryEncoder();

        assertEquals(encoder.encode(StoreFixtures.SOLAR), encoder.encode(StoreFixtures.signal(1.0)));
        assertNotEquals(encoder.encode(StoreFixtures.SOLAR), encoder.encode(StoreFixtures.OCEAN));
    }

    @Test
    void hashedDecoderIsDeterministicAndProducesValidSignals() {
        var decoder = new HashedSignalDecoder();

        var signal = decoder.decode("solar energy");

        assertEquals(signal, decoder.decode("solar energy"));
        assertNotEquals(signal, decoder.decode("ocean tide"));
        assertEquals(SignalKind.INTERMEDIATE, signal.kind());
        assertTrue(signal.frequencyState().amplitude() <= 1.0);
    }
}
