package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskRendererTest {

    private final FixtureCatalogue catalogue = new FixtureCatalogue();
    private final FixtureDigests digests = new FixtureDigests();
    private final MaskRenderer masks = new MaskRenderer();

    private byte[] render(String id, int n) {
        return masks.render(catalogue.find(id).orElseThrow(), n, digests.seed(id));
    }

    private boolean lit(byte[] mask, int n, int x, int y) {
        return mask[y * n + x] != 0;
    }

    private int count(byte[] mask) {
        var lit = 0;
        for (byte value : mask) {
            assertTrue(value == 0 || value == MaskRenderer.LIT, "a mask byte is 0 or 255");
            lit += value == 0 ? 0 : 1;
        }
        return lit;
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void theBasePoseSquareLightsThePixelsWhoseCentersLieInsideItsClosedEdges(int n) {
        byte[] mask = render("m-pos-09", n);

        int side = 3 * n / 4;
        assertEquals(side * side, count(mask));
        assertTrue(lit(mask, n, n / 8, n / 8));
        assertFalse(lit(mask, n, n / 8 - 1, n / 8));
        assertTrue(lit(mask, n, 7 * n / 8 - 1, 7 * n / 8 - 1));
        assertFalse(lit(mask, n, 7 * n / 8, 7 * n / 8 - 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void theBasePoseCircleIsSymmetricAndStaysInsideItsBoundingSquare(int n) {
        byte[] mask = render("m-pos-01", n);

        for (var y = 0; y < n; y++) {
            for (var x = 0; x < n; x++) {
                assertEquals(lit(mask, n, x, y), lit(mask, n, n - 1 - x, y));
                assertEquals(lit(mask, n, x, y), lit(mask, n, y, x));
            }
        }
        assertTrue(lit(mask, n, n / 2, n / 2));
        assertTrue(lit(mask, n, n / 8, n / 2));
        assertFalse(lit(mask, n, n / 8 - 1, n / 2));
        assertFalse(lit(mask, n, n / 8, n / 8));
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void aTranslationMovesTheCircleByWholePixelsWhenItIsAMultipleOfThePixelSize(int n) {
        MaskRenderer.Projection base = masks.projection(new GoldTransform(0, 0, 0.75, 0), n);
        MaskRenderer.Projection moved = masks.projection(new GoldTransform(0.25, 0, 0.75, 0), n);

        assertEquals(base.u(4, 9), moved.u(4 + n / 4, 9));
        assertEquals(base.v(4, 9), moved.v(4 + n / 4, 9));
    }

    @Test
    void aPositiveRotationTurnsTheShapeClockwiseOnScreen() {
        var apexUp = new GoldShape.Triangle(2048, 1024, 4096, 3072, 0, 3072);
        byte[] turned = masks.renderShape(apexUp, new GoldTransform(0, 0, 0.75, Math.PI / 2), 64);

        // The apex pointed up; a quarter turn clockwise on screen points it right, so the wide base is on the left.
        assertTrue(lit(turned, 64, 42, 32));
        assertFalse(lit(turned, 64, 16, 32));
        assertTrue(lit(turned, 64, 22, 12));
        assertTrue(lit(turned, 64, 22, 51));
        assertFalse(lit(turned, 64, 42, 12));
        assertFalse(lit(turned, 64, 42, 51));
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void theSliverLightsExactlyTheCenterRowBetweenAnEighthAndSevenEighths(int n) {
        byte[] mask = render("m-neg-14", n);

        for (var y = 0; y < n; y++) {
            for (var x = 0; x < n; x++) {
                assertEquals(y == n / 2 && x >= n / 8 && x <= 7 * n / 8 - 1, lit(mask, n, x, y), x + "," + y);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void theDegenerateMasksLightTheStatedPixels(int n) {
        assertEquals(0, count(render("m-neg-01", n)));
        assertEquals(n * n, count(render("m-neg-02", n)));
        byte[] almostFull = render("m-neg-03", n);
        assertEquals(n * n - 1, count(almostFull));
        assertFalse(lit(almostFull, n, n / 2, n / 2));
        assertEquals(1, count(render("m-neg-06", n)));
        assertTrue(lit(render("m-neg-06", n), n, 0, 0));
        assertEquals(n, count(render("m-neg-08", n)));
        assertEquals(n * n / 2, count(render("m-neg-09", n)));
        assertTrue(lit(render("m-neg-09", n), n, 0, 0));
        assertEquals(n, count(render("m-neg-13", n)));
        assertTrue(lit(render("m-neg-13", n), n, n / 2, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void theSeededMasksDrawOneLongPerPixelInRasterOrder(int n) {
        long seed = 5137362645076074028L;
        var random = new java.util.SplittableRandom(seed);
        byte[] mask = render("m-neg-10", n);

        assertEquals(seed, digests.seed("m-neg-10"));
        for (var i = 0; i < n * n; i++) {
            boolean expected = (random.nextLong() >>> 11) * 0x1.0p-53 < 0.5;
            assertEquals(expected, mask[i] != 0, "pixel " + i);
        }
        assertTrue(count(render("m-neg-12", n)) < n * n / 10, "salt lights about one pixel in twenty");
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void theCircleWithACenterHoleIsTheBaseCircleMinusItsFourCenterPixels(int n) {
        byte[] holed = render("m-amb-07", n);
        byte[] circle = render("m-pos-01", n);

        assertEquals(count(circle) - 4, count(holed));
        for (int y = n / 2 - 1; y <= n / 2; y++) {
            for (int x = n / 2 - 1; x <= n / 2; x++) {
                assertFalse(lit(holed, n, x, y));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void aCompositeIsItsPartsMinusItsHoles(int n) {
        byte[] ring = render("m-cmp-05", n);
        byte[] circle = render("m-pos-01", n);

        assertFalse(lit(ring, n, n / 2, n / 2));
        assertTrue(lit(ring, n, n / 8, n / 2));
        assertTrue(count(ring) < count(circle));
        for (var i = 0; i < ring.length; i++) {
            assertTrue(ring[i] == 0 || circle[i] != 0, "a ring pixel is a circle pixel");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    void theRegularPolygonsAreMirrorSymmetricAboutTheVerticalAxis(int n) {
        for (String id : new String[] {"m-amb-05", "m-amb-06"}) {
            byte[] mask = render(id, n);
            for (var y = 0; y < n; y++) {
                for (var x = 0; x < n / 2; x++) {
                    assertEquals(lit(mask, n, x, y), lit(mask, n, n - 1 - x, y), id + " " + x + "," + y);
                }
            }
            assertTrue(lit(mask, n, n / 2, n / 2));
            assertFalse(lit(mask, n, 0, 0));
        }
        assertNotEquals(digests.sha256Hex(render("m-amb-05", n)), digests.sha256Hex(render("m-amb-06", n)));
    }

    @Test
    void aPoseThatCrossesTheRasterEdgeIsClipped() {
        FixtureDefinition centered = catalogue.find("m-pos-01").orElseThrow();
        FixtureDefinition shifted = catalogue.find("m-pos-08").orElseThrow();

        assertFalse(masks.clipped(centered.shape().orElseThrow(), centered.transform().orElseThrow(), 32));
        assertTrue(masks.clipped(shifted.shape().orElseThrow(), shifted.transform().orElseThrow(), 32));
    }

    @Test
    void aCanonicalPointOutsideTheUnitSquareIsBackground() {
        var circle = new GoldShape.Circle(2048, 2048, 4096);

        assertTrue(masks.covers(circle, 1.0, 0.5));
        assertFalse(masks.covers(circle, 1.0001, 0.5));
        assertFalse(masks.covers(circle, 0.5, -0.0001));
    }

    @Test
    void anRgbIdIsNotAMaskFixture() {
        FixtureDefinition color = catalogue.find("c-flat-04").orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> masks.render(color, 32, 0L));
    }
}
