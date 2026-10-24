package monada.neuron.spectral;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaveComponentTest {

    private static final double PI = StrictMath.PI;

    @Test
    void acceptsCanonicalValuesIncludingPi() {
        var component = new WaveComponent(0, 4, 1.0, PI, true, 4.0);

        assertEquals(PI, component.phase());
        assertEquals(4.0, component.locator());
    }

    @Test
    void canonicalizesSignedZeros() {
        var negative = new WaveComponent(0, 0, -0.0, -0.0, false, -0.0);

        assertEquals(new WaveComponent(0, 0, 0.0, 0.0, false, 0.0), negative);
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(negative.magnitude()));
    }

    @Test
    void rejectsInvalidValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveComponent(-1, 0, 1.0, 0.0, true, 0.0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveComponent(0, -1, 1.0, 0.0, true, 0.0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveComponent(0, 0, -1.0, 0.0, true, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WaveComponent(0, 0, Double.NaN, 0.0, true, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WaveComponent(0, 0, Double.POSITIVE_INFINITY, 0.0, true, 0.0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveComponent(0, 0, 1.0, -PI, true, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WaveComponent(0, 0, 1.0, Math.nextUp(PI), true, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WaveComponent(0, 0, 1.0, Double.NaN, true, 0.0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveComponent(0, 0, 1.0, 0.0, true, -1.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WaveComponent(0, 0, 1.0, 0.0, true, Double.POSITIVE_INFINITY)));
    }
}
