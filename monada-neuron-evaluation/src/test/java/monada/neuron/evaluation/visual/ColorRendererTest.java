package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorRendererTest {

    private final FixtureCatalogue catalogue = new FixtureCatalogue();
    private final FixtureRenderer renderer = new FixtureRenderer(catalogue);
    private final ColorRenderer colors = new ColorRenderer(new MaskRenderer());

    private byte[] render(String id, int n) {
        return renderer.pixels(catalogue.find(id).orElseThrow(), n);
    }

    private int[] pixel(byte[] rgba, int n, int x, int y) {
        int at = (y * n + x) * 4;
        return new int[] {rgba[at] & 0xff, rgba[at + 1] & 0xff, rgba[at + 2] & 0xff, rgba[at + 3] & 0xff};
    }

    @Test
    void encodingRoundsToTheNearestByteAndClamps() {
        assertEquals(0, colors.encode(0.0) & 0xff);
        assertEquals(255, colors.encode(1.0) & 0xff);
        assertEquals(128, colors.encode(128 / 255.0) & 0xff);
        assertEquals(128, colors.encode(0.5) & 0xff);
        assertEquals(1, colors.encode(1 / 255.0) & 0xff);
        assertEquals(0, colors.encode(-0.25) & 0xff);
        assertEquals(255, colors.encode(1.5) & 0xff);
    }

    @Test
    void theTransferFunctionsUseTheLinearBranchBelowTheBreakpoint() {
        assertEquals(0.04045 / 12.92, colors.srgbToLinear(0.04045));
        assertEquals(10 / 255.0 / 12.92, colors.srgbToLinear(10 / 255.0));
        assertEquals(0.04045, colors.linearToSrgb(0.04045 / 12.92), 1e-15);
        assertEquals(1.0, colors.linearToSrgb(colors.srgbToLinear(1.0)), 1e-12);
        assertEquals(0.5, colors.linearToSrgb(colors.srgbToLinear(0.5)), 1e-12);
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void aFlatFixtureHoldsOneColorInRgbaOrder(int n) {
        byte[] red = render("c-flat-04", n);

        assertEquals(n * n * 4, red.length);
        for (var y = 0; y < n; y++) {
            for (var x = 0; x < n; x++) {
                assertArrayEquals(new int[] {255, 0, 0, 255}, pixel(red, n, x, y));
            }
        }
        assertArrayEquals(new int[] {0, 255, 0, 255}, pixel(render("c-chan-02", n), n, 3, 5));
        assertArrayEquals(new int[] {10, 0, 0, 255}, pixel(render("c-chan-01", n), n, 3, 5));
        assertArrayEquals(new int[] {128, 128, 128, 128}, pixel(render("c-chan-06", n), n, 3, 5));
        assertArrayEquals(new int[] {255, 255, 255, 0}, pixel(render("c-deg-02", n), n, 3, 5));
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void theNyquistToneIsOneAtEvenColumnsAndZeroAtOddColumns(int n) {
        byte[] tone = render("c-tone-06", n);

        for (var y = 0; y < n; y++) {
            for (var x = 0; x < n; x++) {
                int value = x % 2 == 0 ? 255 : 0;
                assertArrayEquals(new int[] {value, value, value, 255}, pixel(tone, n, x, y));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void gradientsCheckerboardsStepsAndImpulsesFollowTheirFormulas(int n) {
        byte[] vertical = render("c-grad-02", n);
        assertArrayEquals(new int[] {0, 0, 0, 255}, pixel(vertical, n, 7, 0));
        assertArrayEquals(new int[] {255, 255, 255, 255}, pixel(vertical, n, 7, n - 1));

        byte[] channels = render("c-grad-05", n);
        assertArrayEquals(new int[] {255, 0, 128, 255}, pixel(channels, n, n - 1, 0));

        byte[] alpha = render("c-grad-06", n);
        assertArrayEquals(new int[] {255, 0, 0, 0}, pixel(alpha, n, 0, 4));
        assertArrayEquals(new int[] {255, 0, 0, 255}, pixel(alpha, n, n - 1, 4));

        byte[] checker = render("c-chk-03", n);
        assertArrayEquals(new int[] {0, 0, 0, 255}, pixel(checker, n, 0, 0));
        assertArrayEquals(new int[] {0, 0, 0, 255}, pixel(checker, n, 3, 3));
        assertArrayEquals(new int[] {255, 255, 255, 255}, pixel(checker, n, 4, 3));
        assertArrayEquals(new int[] {0, 0, 0, 255}, pixel(checker, n, 4, 4));

        byte[] step = render("c-step-02", n);
        int boundary = n == 32 ? 10 : 20;
        assertArrayEquals(new int[] {0, 0, 0, 255}, pixel(step, n, boundary - 1, 0));
        assertArrayEquals(new int[] {255, 255, 255, 255}, pixel(step, n, boundary, 0));

        byte[] impulse = render("c-imp-01", n);
        assertArrayEquals(new int[] {255, 255, 255, 255}, pixel(impulse, n, n / 2, n / 2));
        assertArrayEquals(new int[] {0, 0, 0, 255}, pixel(impulse, n, n / 2 - 1, n / 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void seededNoiseTakesTheTopByteOfOneLongPerChannel(int n) {
        var random = new SplittableRandom(-4199152380548194853L);
        byte[] opaque = render("c-deg-04", n);

        for (var at = 0; at < opaque.length; at += 4) {
            assertEquals((byte) (int) (random.nextLong() >>> 56), opaque[at]);
            assertEquals((byte) (int) (random.nextLong() >>> 56), opaque[at + 1]);
            assertEquals((byte) (int) (random.nextLong() >>> 56), opaque[at + 2]);
            assertEquals((byte) 255, opaque[at + 3]);
        }

        var withAlpha = new SplittableRandom(-3897877353076230856L);
        byte[] translucent = render("c-deg-05", n);
        for (var at = 0; at < translucent.length; at++) {
            assertEquals((byte) (int) (withAlpha.nextLong() >>> 56), translucent[at]);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void anOpaqueLayerOverABackgroundKeepsBothFlatColors(int n) {
        byte[] image = render("c-shp-06", n);

        assertArrayEquals(new int[] {200, 200, 200, 255}, pixel(image, n, 0, 0));
        assertArrayEquals(new int[] {255, 0, 0, 255}, pixel(image, n, n / 2, n / 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void halfAlphaLayersCompositeSourceOverInLinearLight(int n) {
        byte[] image = render("c-shp-05", n);

        assertArrayEquals(new int[] {0, 0, 0, 0}, pixel(image, n, 0, 0));
        assertArrayEquals(new int[] {255, 0, 0, 128}, pixel(image, n, n / 4, n / 2));
        assertArrayEquals(new int[] {0, 0, 255, 128}, pixel(image, n, 3 * n / 4, n / 2));
        // Blue at half alpha over red at half alpha: linear red 1/3 and blue 2/3 at alpha 0.75.
        assertArrayEquals(new int[] {156, 0, 213, 191}, pixel(image, n, n / 2, n / 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void aTextureIsSampledInCanonicalShapeSpaceWithNearestNeighbour(int n) {
        byte[] image = render("c-shp-07", n);
        byte[] texture = render("c-chk-03", n);

        assertArrayEquals(new int[] {0, 0, 0, 0}, pixel(image, n, 0, 0));
        for (int y = n / 8; y < 7 * n / 8; y++) {
            for (int x = n / 8; x < 7 * n / 8; x++) {
                double u = ((x + 0.5) / n - 0.5) / 0.75 + 0.5;
                double v = ((y + 0.5) / n - 0.5) / 0.75 + 0.5;
                int[] texel = pixel(texture, n, (int) Math.floor(u * n), (int) Math.floor(v * n));
                assertArrayEquals(texel, pixel(image, n, x, y), x + "," + y);
            }
        }
    }
}
