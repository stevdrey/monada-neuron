package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixturePngTest {

    private final FixturePng png = new FixturePng();

    @TempDir
    Path directory;

    private Path write(BufferedImage image, String name) throws IOException {
        Path file = directory.resolve(name);
        assertTrue(ImageIO.write(image, "png", file.toFile()));
        return file;
    }

    @Test
    void aMaskRoundTripsThroughAnEightBitGrayPng() throws IOException {
        var mask = new byte[32 * 32];
        mask[0] = MaskRenderer.LIT;
        mask[33] = MaskRenderer.LIT;
        mask[1023] = MaskRenderer.LIT;
        Path file = directory.resolve("mask.png");

        png.writeMask(file, mask, 32);

        assertArrayEquals(mask, png.readMask(file));
    }

    @Test
    void anRgbaImageRoundTripsWithStraightAlphaInRgbaOrder() throws IOException {
        var rgba = new byte[64 * 64 * 4];
        for (var i = 0; i < rgba.length; i++) {
            rgba[i] = (byte) (i * 31 + i / 7);
        }
        Path file = directory.resolve("color.png");

        png.writeRgba(file, rgba, 64);

        assertArrayEquals(rgba, png.readRgba(file));
    }

    @Test
    void aMaskPngThatIsNotEightBitGrayThrows() throws IOException {
        Path color = write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "color.png");
        Path deep = write(new BufferedImage(32, 32, BufferedImage.TYPE_USHORT_GRAY), "deep.png");

        var notGray = assertThrows(IllegalStateException.class, () -> png.readMask(color));
        assertTrue(notGray.getMessage().contains("color.png is not an 8 bit grayscale PNG"), notGray.getMessage());
        assertThrows(IllegalStateException.class, () -> png.readMask(deep));
    }

    @Test
    void aMaskPngOfAnotherSizeThrows() throws IOException {
        Path small = write(new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_GRAY), "small.png");
        Path wide = write(new BufferedImage(64, 32, BufferedImage.TYPE_BYTE_GRAY), "wide.png");

        var wrongSize = assertThrows(IllegalStateException.class, () -> png.readMask(small));
        assertTrue(wrongSize.getMessage().contains("small.png is 16x16"), wrongSize.getMessage());
        assertThrows(IllegalStateException.class, () -> png.readMask(wide));
    }

    @Test
    void aMaskPngWithAValueOtherThanZeroOr255Throws() throws IOException {
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY);
        image.getRaster().setSample(5, 2, 0, 254);
        Path file = write(image, "soft.png");

        var softEdge = assertThrows(IllegalStateException.class, () -> png.readMask(file));
        assertTrue(softEdge.getMessage().contains("holds the mask value 254 at pixel (5, 2)"), softEdge.getMessage());
    }

    @Test
    void aFileThatIsNotAnImageThrows() throws IOException {
        Path file = directory.resolve("text.png");
        java.nio.file.Files.writeString(file, "not a png");

        assertThrows(IllegalStateException.class, () -> png.readMask(file));
        assertThrows(IllegalStateException.class, () -> png.readRgba(file));
    }

    @Test
    void anRgbaReadOfAGrayPngThrows() throws IOException {
        Path gray = write(new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY), "gray.png");

        assertThrows(IllegalStateException.class, () -> png.readRgba(gray));
    }
}
