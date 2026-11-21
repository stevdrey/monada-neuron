package monada.neuron.visual;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static monada.neuron.visual.VisualFixtures.ok;
import static monada.neuron.visual.VisualFixtures.rejection;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 0012 AC-3, AC-4 and AC-5 for {@link BinaryMask}; bit layout of contract Section 3.3. */
class BinaryMaskTest {

    @Test
    void storesBitYTimesWidthPlusXLeastSignificantFirst() {
        var mask = ok(BinaryMask.of(3, 2, new long[] {0b100_010}));

        assertAll(
                () -> assertEquals(3, mask.width()),
                () -> assertEquals(2, mask.height()),
                () -> assertEquals(MaskArt.art(".#.", "..#"), MaskArt.render(mask)),
                () -> assertEquals(2, mask.cardinality()));
    }

    @Test
    void spansWordsRowMajor() {
        long[] words = new long[64];
        words[1] = 1L;
        words[63] = 1L << 63;
        var mask = ok(BinaryMask.of(64, 64, words));

        assertAll(
                () -> assertTrue(mask.get(0, 1)),
                () -> assertTrue(mask.get(63, 63)),
                () -> assertFalse(mask.get(0, 0)),
                () -> assertEquals(2, mask.cardinality()));
    }

    @Test
    void rejectsUnsupportedSizesWithoutThrowing() {
        int[] unsupported = {0, -1, 65, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int size : unsupported) {
            assertAll("size " + size,
                    () -> assertEquals(VisualRejectionReason.SIZE_UNSUPPORTED,
                            rejection(BinaryMask.of(size, 5, new long[1]))),
                    () -> assertEquals(VisualRejectionReason.SIZE_UNSUPPORTED,
                            rejection(BinaryMask.of(5, size, new long[1]))),
                    () -> assertEquals(VisualRejectionReason.SIZE_UNSUPPORTED,
                            rejection(BinaryMask.of(size, size, new long[0]))));
        }
    }

    @Test
    void acceptsEverySizeFromOneToSixtyFourIncludingRectangles() {
        assertAll(
                () -> assertEquals(1, ok(BinaryMask.of(1, 1, new long[] {1L})).cardinality()),
                () -> assertEquals(64, ok(BinaryMask.of(64, 1, new long[] {-1L})).cardinality()),
                () -> assertEquals(0, ok(BinaryMask.of(5, 13, new long[2])).cardinality()),
                () -> assertEquals(0, ok(BinaryMask.of(64, 64, new long[64])).cardinality()));
    }

    @Test
    void throwsForANullArrayBeforeTheSizeCheck() {
        assertThrows(IllegalArgumentException.class, () -> BinaryMask.of(4, 4, null));
        assertThrows(IllegalArgumentException.class, () -> BinaryMask.of(0, 4, null));
    }

    @Test
    void throwsForAWrongWordCount() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> BinaryMask.of(8, 8, new long[2])),
                () -> assertThrows(IllegalArgumentException.class, () -> BinaryMask.of(8, 8, new long[0])),
                () -> assertThrows(IllegalArgumentException.class, () -> BinaryMask.of(5, 13, new long[1])),
                () -> assertThrows(IllegalArgumentException.class, () -> BinaryMask.of(64, 64, new long[65])));
    }

    @Test
    void throwsForNonzeroTailBits() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> BinaryMask.of(3, 3, new long[] {1L << 9})),
                () -> assertThrows(IllegalArgumentException.class, () -> BinaryMask.of(3, 3, new long[] {1L << 63})),
                () -> assertThrows(IllegalArgumentException.class, () -> BinaryMask.of(5, 13, new long[] {0L, 2L})),
                () -> assertEquals(9, ok(BinaryMask.of(3, 3, new long[] {0x1FFL})).cardinality()),
                () -> assertEquals(65, ok(BinaryMask.of(5, 13, new long[] {-1L, 1L})).cardinality()));
    }

    @Test
    void getChecksEachCoordinateSeparately() {
        var mask = ok(BinaryMask.of(3, 2, new long[] {0b111_111}));

        assertAll(
                // x = 3 on a 3 wide mask would alias pixel (0, 1) through a combined bit index.
                () -> assertThrows(IndexOutOfBoundsException.class, () -> mask.get(3, 0)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> mask.get(-1, 0)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> mask.get(0, 2)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> mask.get(0, -1)));
    }

    @Test
    void neverAliasesTheCallerArrayOrItsOwnWords() {
        long[] source = {0b0110};
        var mask = ok(BinaryMask.of(2, 2, source));

        source[0] = 0b1111;
        mask.words()[0] = 0;

        assertAll(
                () -> assertArrayEquals(new long[] {0b0110}, mask.words()),
                () -> assertEquals(MaskArt.art(".#", "#."), MaskArt.render(mask)));
    }

    @Test
    void isAValueOverItsSizeAndPixels() {
        var mask = ok(BinaryMask.of(2, 3, new long[] {0b101}));
        var same = ok(BinaryMask.of(2, 3, new long[] {0b101}));

        assertAll(
                () -> assertEquals(mask, same),
                () -> assertEquals(mask.hashCode(), same.hashCode()),
                () -> assertNotEquals(mask, ok(BinaryMask.of(3, 2, new long[] {0b101}))),
                () -> assertNotEquals(mask, ok(BinaryMask.of(2, 3, new long[] {0b111}))),
                () -> assertNotEquals(mask, "BinaryMask"),
                () -> assertEquals(31 * (31 * 2 + 3) + Arrays.hashCode(new long[] {0b101}), mask.hashCode()));
    }

    @Test
    void toStringNamesTheSizeAndCountButPrintsNoBits() {
        assertEquals("BinaryMask[2x3, cardinality=2]", ok(BinaryMask.of(2, 3, new long[] {0b101})).toString());
    }
}
