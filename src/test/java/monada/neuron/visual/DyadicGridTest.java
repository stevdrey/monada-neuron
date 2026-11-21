package monada.neuron.visual;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract Section 4.8 item 1 and the {@code 2^28} numerator bound of the {@code /1} change record. */
class DyadicGridTest {

    @Test
    void acceptsMultiplesOfTheStepUpToTheNumeratorBound() {
        assertTrue(DyadicGrid.isOnGrid(0.0));
        assertTrue(DyadicGrid.isOnGrid(-0.0));
        assertTrue(DyadicGrid.isOnGrid(1.0 / 4096));
        assertTrue(DyadicGrid.isOnGrid(-3.5));
        assertTrue(DyadicGrid.isOnGrid(65536.0));
        assertTrue(DyadicGrid.isOnGrid(-65536.0));
    }

    @Test
    void rejectsOffGridOutOfBoundAndNonfiniteValues() {
        assertFalse(DyadicGrid.isOnGrid(0.45));
        assertFalse(DyadicGrid.isOnGrid(1.0 / 8192));
        assertFalse(DyadicGrid.isOnGrid(65536.0 + 1.0 / 4096));
        assertFalse(DyadicGrid.isOnGrid(1e300));
        assertFalse(DyadicGrid.isOnGrid(Double.NaN));
        assertFalse(DyadicGrid.isOnGrid(Double.POSITIVE_INFINITY));
        assertFalse(DyadicGrid.isOnGrid(Double.NEGATIVE_INFINITY));
    }

    @Test
    void snapsToTheNearestStepWithTiesToEven() {
        assertEquals(1843.0 / 4096, DyadicGrid.snap(0.45));
        assertEquals(2048.0 / 4096, DyadicGrid.snap(2047.5 / 4096));
        assertEquals(2048.0 / 4096, DyadicGrid.snap(2048.5 / 4096));
        assertEquals(2050.0 / 4096, DyadicGrid.snap(2049.5 / 4096));
    }

    @Test
    void convertsBetweenValuesAndNumerators() {
        assertEquals(3821L, DyadicGrid.numerator(3821.0 / 4096));
        assertEquals(-(1L << 28), DyadicGrid.numerator(-65536.0));
        assertEquals(275.0 / 4096, DyadicGrid.ofNumerator(275));
    }

    @Test
    void requireOnGridStoresNegativeZeroAsPositiveZero() {
        assertEquals(0L, Double.doubleToRawLongBits(DyadicGrid.requireOnGrid(-0.0, "v")));
        var error = assertThrows(IllegalArgumentException.class, () -> DyadicGrid.requireOnGrid(0.1, "side"));
        assertTrue(error.getMessage().startsWith("side "), error.getMessage());
    }
}
