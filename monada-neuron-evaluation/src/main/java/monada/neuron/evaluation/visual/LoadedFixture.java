package monada.neuron.evaluation.visual;

import java.util.Arrays;
import java.util.Objects;

/**
 * One regenerated fixture whose pixels were checked against its manifest row. The bytes are the decoded pixels in
 * raster order: one channel for a mask ({@code 0} or {@code 255}), four interleaved channels {@code R, G, B, A} for an
 * RGB fixture. That is exactly what the row's {@code pixel_sha256} hashes.
 *
 * <p>The constructor makes the check itself, so no caller can hold a fixture whose sizes disagree with its row or
 * whose pixels do not hash to it, whoever built it (issue #217). It proves that the bytes agree with the row the
 * fixture carries. It does not prove that the row is one of the frozen manifest: {@link ManifestRow} is a public
 * record, and only {@link VisualFixtureCorpus} hands out fixtures whose rows come from the manifest. Two fixtures are
 * equal when their rows and their pixel bytes are equal.
 *
 * @param row the manifest row
 * @param width the raster width; the row's size
 * @param height the raster height; the row's size
 * @param channels bytes per pixel; the channel count of the row's kind
 * @param pixels the decoded bytes; copied in and copied out
 */
public record LoadedFixture(ManifestRow row, int width, int height, int channels, byte[] pixels) {

    /**
     * @throws IllegalArgumentException when the sizes disagree with the row or with the number of pixel bytes
     * @throws ArithmeticException when the sizes of a forged row overflow an {@code int}
     * @throws IllegalStateException naming the id and size when the pixels do not hash to the row
     */
    public LoadedFixture {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(pixels, "pixels");
        String name = "fixture " + row.id() + " at size " + row.size();
        if (width != row.size() || height != row.size() || channels != row.kind().channels()) {
            throw new IllegalArgumentException(
                    name + " cannot be " + width + " by " + height + " with " + channels + " channels");
        }
        int expected = Math.multiplyExact(Math.multiplyExact(width, height), channels);
        if (pixels.length != expected) {
            throw new IllegalArgumentException(
                    name + " needs " + expected + " pixel bytes, got " + pixels.length);
        }
        pixels = pixels.clone();
        String actual = new FixtureDigests().sha256Hex(pixels);
        if (!actual.equals(row.pixelSha256())) {
            throw new IllegalStateException(name + " regenerated to pixel hash " + actual
                    + " but its manifest row says " + row.pixelSha256());
        }
    }

    /** A copy of the decoded bytes. */
    @Override
    public byte[] pixels() {
        return pixels.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LoadedFixture that && row.equals(that.row) && width == that.width
                && height == that.height && channels == that.channels && Arrays.equals(pixels, that.pixels);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(row, width, height, channels) + Arrays.hashCode(pixels);
    }

    @Override
    public String toString() {
        return "LoadedFixture[" + row.id() + " at size " + row.size() + ", " + pixels.length + " bytes]";
    }
}
