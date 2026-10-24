package monada.neuron.spectral;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectralPhaseTest {

    private static final double PI = StrictMath.PI;

    @Test
    void wrapsIntoTheHalfOpenInterval() {
        assertAll(
                () -> assertEquals(PI, SpectralPhase.wrap(PI)),
                () -> assertEquals(PI, SpectralPhase.wrap(-PI)),
                () -> assertEquals(0.0, SpectralPhase.wrap(2 * PI)),
                () -> assertEquals(1.0, SpectralPhase.wrap(1.0)),
                () -> assertEquals(-1.0, SpectralPhase.wrap(-1.0)),
                () -> assertEquals(PI / 2, SpectralPhase.wrap(PI / 2 + 4 * PI), 1e-12),
                () -> assertEquals(5.0 - 2 * PI, SpectralPhase.wrap(5.0), 1e-12),
                () -> assertTrue(SpectralPhase.isCanonical(SpectralPhase.wrap(1e9))),
                () -> assertTrue(SpectralPhase.isCanonical(SpectralPhase.wrap(-1e9))));
    }

    @Test
    void canonicalizesNegativeZero() {
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(SpectralPhase.wrap(-0.0)));
        assertFalse(SpectralPhase.isCanonical(-0.0));
        assertFalse(SpectralPhase.isCanonical(-PI));
        assertFalse(SpectralPhase.isCanonical(Double.NaN));
        assertTrue(SpectralPhase.isCanonical(PI));
    }

    @Test
    void rejectsNonFinitePhases() {
        assertThrows(IllegalArgumentException.class, () -> SpectralPhase.wrap(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> SpectralPhase.wrap(Double.NEGATIVE_INFINITY));
    }
}
