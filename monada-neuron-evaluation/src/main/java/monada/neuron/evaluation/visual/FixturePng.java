package monada.neuron.evaluation.visual;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes and reads fixture PNG files through {@code javax.imageio}. Samples always go through the raster, never
 * through {@code getRGB}, which converts gray values and would change mask bytes. RGBA images use straight alpha.
 */
final class FixturePng {

    private static final int RGBA_CHANNELS = 4;

    /** Writes a mask as an 8 bit grayscale PNG. */
    void writeMask(Path file, byte[] mask, int n) throws IOException {
        var image = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        image.getRaster().setSamples(0, 0, n, n, 0, unsigned(mask));
        write(image, file);
    }

    /** Writes interleaved {@code R, G, B, A} bytes as an 8 bit RGBA PNG. */
    void writeRgba(Path file, byte[] rgba, int n) throws IOException {
        var image = new BufferedImage(n, n, BufferedImage.TYPE_4BYTE_ABGR);
        image.getRaster().setPixels(0, 0, n, n, unsigned(rgba));
        write(image, file);
    }

    /**
     * Reads a mask PNG.
     *
     * @param file the PNG file
     * @return the mask bytes in raster order, each {@code 0} or {@code 255}
     * @throws IllegalStateException when the image is not 8 bit gray, is not 32x32 or 64x64, or holds another value
     */
    byte[] readMask(Path file) throws IOException {
        BufferedImage image = read(file);
        if (image.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            throw new IllegalStateException(file.getFileName() + " is not an 8 bit grayscale PNG");
        }
        int n = size(image, file);
        int[] samples = image.getRaster().getSamples(0, 0, n, n, 0, (int[]) null);
        var mask = new byte[samples.length];
        for (var i = 0; i < samples.length; i++) {
            if (samples[i] != 0 && samples[i] != 255) {
                throw new IllegalStateException(file.getFileName() + " holds the mask value " + samples[i]
                        + " at pixel (" + i % n + ", " + i / n + "); only 0 and 255 are allowed");
            }
            mask[i] = (byte) samples[i];
        }
        return mask;
    }

    /**
     * Reads an RGBA PNG.
     *
     * @param file the PNG file
     * @return interleaved {@code R, G, B, A} bytes in raster order
     * @throws IllegalStateException when the image is not 8 bit RGBA or is not 32x32 or 64x64
     */
    byte[] readRgba(Path file) throws IOException {
        BufferedImage image = read(file);
        WritableRaster raster = image.getRaster();
        if (raster.getNumBands() != RGBA_CHANNELS || image.getColorModel().getPixelSize() != 32
                || image.isAlphaPremultiplied()) {
            throw new IllegalStateException(file.getFileName() + " is not an 8 bit RGBA PNG with straight alpha");
        }
        int n = size(image, file);
        int[] samples = raster.getPixels(0, 0, n, n, (int[]) null);
        var rgba = new byte[samples.length];
        for (var i = 0; i < samples.length; i++) {
            rgba[i] = (byte) samples[i];
        }
        return rgba;
    }

    private int size(BufferedImage image, Path file) {
        int n = image.getWidth();
        if (image.getHeight() != n || !FixtureCatalogue.SIZES.contains(n)) {
            throw new IllegalStateException(file.getFileName() + " is " + image.getWidth() + "x" + image.getHeight()
                    + "; a fixture is 32x32 or 64x64");
        }
        return n;
    }

    private BufferedImage read(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IllegalStateException(file.getFileName() + " is not a readable image");
        }
        return image;
    }

    private void write(BufferedImage image, Path file) throws IOException {
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IllegalStateException("no PNG writer is available for " + file.getFileName());
        }
    }

    private int[] unsigned(byte[] bytes) {
        var samples = new int[bytes.length];
        for (var i = 0; i < bytes.length; i++) {
            samples[i] = bytes[i] & 0xff;
        }
        return samples;
    }
}
