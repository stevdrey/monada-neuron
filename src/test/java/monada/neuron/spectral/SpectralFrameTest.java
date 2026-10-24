package monada.neuron.spectral;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectralFrameTest {

    @Test
    void scaleIsBoundedByTheHaarDepth() {
        assertEquals(1, SpectralScale.SINGLE.levelCount());
        assertEquals(13, new SpectralScale(SpectralScale.MAX_HAAR_LEVELS).levelCount());
        assertThrows(IllegalArgumentException.class, () -> new SpectralScale(-1));
        assertThrows(IllegalArgumentException.class, () -> new SpectralScale(13));
    }

    @Test
    void rejectsInvalidSamplingMetadata() {
        assertAll(
                () -> assertInvalid(SpectralBasis.DFT, 0, 0, 8.0, SampleUnit.HERTZ),
                () -> assertInvalid(SpectralBasis.DFT, 0, SpectralPattern.MAX_SAMPLE_COUNT + 1, 8.0, SampleUnit.HERTZ),
                () -> assertInvalid(SpectralBasis.DFT, 0, 8, 0.0, SampleUnit.HERTZ),
                () -> assertInvalid(SpectralBasis.DFT, 0, 8, -8.0, SampleUnit.HERTZ),
                () -> assertInvalid(SpectralBasis.DFT, 0, 8, Double.NaN, SampleUnit.HERTZ),
                () -> assertInvalid(SpectralBasis.DFT, 0, 8, Double.POSITIVE_INFINITY, SampleUnit.HERTZ),
                () -> assertInvalid(SpectralBasis.DFT, 0, 8, 2.0, SampleUnit.ABSTRACT_PER_INDEX),
                () -> assertInvalid(SpectralBasis.DFT, 0, 8, Double.MAX_VALUE, SampleUnit.HERTZ),
                () -> assertInvalid(SpectralBasis.DFT, 1, 8, 8.0, SampleUnit.HERTZ),
                () -> assertInvalid(SpectralBasis.HAAR, 1, 6, 6.0, SampleUnit.HERTZ),
                () -> assertInvalid(SpectralBasis.HAAR, 0, 8, 8.0, SampleUnit.HERTZ),
                () -> assertInvalid(SpectralBasis.HAAR, 4, 8, 8.0, SampleUnit.HERTZ),
                () -> assertThrows(NullPointerException.class,
                        () -> new SpectralFrame(null, SpectralScale.SINGLE, 8, 8.0, SampleUnit.HERTZ)),
                () -> assertThrows(NullPointerException.class,
                        () -> new SpectralFrame(SpectralBasis.DFT, null, 8, 8.0, SampleUnit.HERTZ)),
                () -> assertThrows(NullPointerException.class,
                        () -> new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 8.0, null)));
    }

    @Test
    void singleSampleDftHasOnlyTheDcBin() {
        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 1, 1.0, SampleUnit.ABSTRACT_PER_INDEX);

        assertEquals(1, frame.componentCapacity());
        assertTrue(frame.containsKey(0, 0));
        assertFalse(frame.containsKey(0, 1));
    }

    @Test
    void haarKeysFollowTheDyadicLevels() {
        var frame = new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(3), 8, 8.0, SampleUnit.HERTZ);

        assertAll(
                () -> assertEquals(8, frame.componentCapacity()),
                () -> assertTrue(frame.containsKey(0, 0)),
                () -> assertFalse(frame.containsKey(0, 1)),
                () -> assertTrue(frame.containsKey(1, 3)),
                () -> assertFalse(frame.containsKey(1, 4)),
                () -> assertTrue(frame.containsKey(3, 0)),
                () -> assertFalse(frame.containsKey(4, 0)),
                () -> assertEquals(6.0, frame.locator(1, 3)),
                () -> assertThrows(IllegalArgumentException.class, () -> frame.locator(1, 4)));
    }

    @Test
    void framesWithDifferentRateUnitOrBasisAreNotEqual() {
        var base = SpectralFixtures.DFT_8;

        assertNotEquals(base, new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 16.0, SampleUnit.HERTZ));
        assertNotEquals(base, new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 1.0,
                SampleUnit.ABSTRACT_PER_INDEX));
        assertNotEquals(base, new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(3), 8, 8.0, SampleUnit.HERTZ));
    }

    private static void assertInvalid(SpectralBasis basis, int levels, int sampleCount, double rate, SampleUnit unit) {
        assertThrows(IllegalArgumentException.class,
                () -> new SpectralFrame(basis, new SpectralScale(levels), sampleCount, rate, unit));
    }
}
