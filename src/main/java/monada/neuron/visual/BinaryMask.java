package monada.neuron.visual;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable binary raster of at most 64x64 pixels, packed one bit per pixel (contract {@code visual-geometry/1}
 * Section 3.3).
 *
 * <p>Bit {@code i = y · width + x} lives in word {@code i >>> 6}, bit {@code i & 63}, least significant bit first.
 * There are exactly {@code ceil(width · height / 64)} words and the unused tail bits are zero, so two masks with the
 * same pixels are equal values. A mask is pixels only: equal masks do not mean equal shapes, and a mask carries no
 * label.
 *
 * <p>The array passed to {@link #of} is copied and {@link #words()} returns a copy, so no caller can reach the
 * stored words.
 */
public final class BinaryMask {

    static final int MAX_SIZE = 64;

    private final int width;
    private final int height;
    private final long[] words;

    /** Takes ownership of {@code words} without copying or checking; callers in this package hand over a fresh array. */
    BinaryMask(int width, int height, long[] words) {
        this.width = width;
        this.height = height;
        this.words = words;
    }

    /**
     * Builds a mask from packed words.
     *
     * @return {@link Result.Ok} with the mask, or {@link VisualRejectionReason#SIZE_UNSUPPORTED} when the width or
     *         height is outside {@code [1, 64]}
     * @throws IllegalArgumentException for a null array, and, after the size check, for a word count other than
     *                                  {@code ceil(width · height / 64)} or a nonzero tail bit
     */
    public static Result<BinaryMask> of(int width, int height, long[] words) {
        if (words == null) {
            throw new IllegalArgumentException("words must not be null");
        }
        if (width < 1 || width > MAX_SIZE || height < 1 || height > MAX_SIZE) {
            return new Result.Rejected<>(new VisualRejection(VisualRejectionReason.SIZE_UNSUPPORTED));
        }
        int bits = width * height;
        int expectedWords = (bits + 63) >>> 6;
        if (words.length != expectedWords) {
            throw new IllegalArgumentException(
                    "a " + width + "x" + height + " mask needs " + expectedWords + " words, got " + words.length);
        }
        // Copy before the tail check: a caller that writes to its array during this call cannot then store a mask
        // with nonzero tail bits, which would break equality between masks with the same pixels.
        long[] copy = words.clone();
        int tailBits = bits & 63;
        if (tailBits != 0 && (copy[expectedWords - 1] >>> tailBits) != 0) {
            throw new IllegalArgumentException("bits beyond the last pixel must be zero");
        }
        return new Result.Ok<>(new BinaryMask(width, height, copy));
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * Reads one pixel without allocating.
     *
     * @throws IndexOutOfBoundsException when {@code x} or {@code y} is outside the raster
     */
    public boolean get(int x, int y) {
        Objects.checkIndex(x, width);
        Objects.checkIndex(y, height);
        int bit = y * width + x;
        return (words[bit >>> 6] >>> bit & 1L) != 0;
    }

    /** The number of foreground pixels. */
    public int cardinality() {
        int count = 0;
        for (long word : words) {
            count += Long.bitCount(word);
        }
        return count;
    }

    /** A copy of the packed words. */
    public long[] words() {
        return words.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BinaryMask mask
                && width == mask.width
                && height == mask.height
                && Arrays.equals(words, mask.words);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(words);
    }

    @Override
    public String toString() {
        return "BinaryMask[" + width + "x" + height + ", cardinality=" + cardinality() + "]";
    }
}
