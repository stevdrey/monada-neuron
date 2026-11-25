package monada.neuron.evaluation.visual;

/**
 * One regenerated fixture whose pixels were checked against its manifest row. The bytes are the decoded pixels in
 * raster order: one channel for a mask ({@code 0} or {@code 255}), four interleaved channels {@code R, G, B, A} for an
 * RGB fixture. That is exactly what the row's {@code pixel_sha256} hashes.
 *
 * @param row the manifest row
 * @param width the raster width
 * @param height the raster height
 * @param channels bytes per pixel
 * @param pixels the decoded bytes; copied in and copied out
 */
public record LoadedFixture(ManifestRow row, int width, int height, int channels, byte[] pixels) {

    public LoadedFixture {
        pixels = pixels.clone();
    }

    /** A copy of the decoded bytes. */
    @Override
    public byte[] pixels() {
        return pixels.clone();
    }
}
