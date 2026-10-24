package monada.neuron.spectral;

import monada.neuron.memory.ResonanceMemoryResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaveProvenanceTest {

    private static final String LONGEST = "e".repeat(ResonanceMemoryResult.MAX_REFERENCE_LENGTH);

    @Test
    void acceptsBoundaryValues() {
        var provenance = new WaveProvenance(LONGEST, 1, LONGEST, 0L);

        assertEquals(128, provenance.encoderId().length());
        assertEquals(0L, provenance.windowOffset());
    }

    @Test
    void rejectsInvalidValues() {
        var tooLong = LONGEST + "e";
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new WaveProvenance(null, 1, "s", 0L)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveProvenance(" ", 1, "s", 0L)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveProvenance(tooLong, 1, "s", 0L)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveProvenance("e", 0, "s", 0L)),
                () -> assertThrows(NullPointerException.class, () -> new WaveProvenance("e", 1, null, 0L)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveProvenance("e", 1, "", 0L)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveProvenance("e", 1, tooLong, 0L)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveProvenance("e", 1, "s", -1L)));
    }
}
