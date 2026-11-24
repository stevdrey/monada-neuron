package monada.neuron.visual;

import org.junit.jupiter.api.Test;

import java.util.List;

import static monada.neuron.visual.MaskArt.art;
import static monada.neuron.visual.MaskArt.render;
import static monada.neuron.visual.VisualFixtures.IDENTITY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Spec 0012 AC-7: the center rule and render arithmetic of contract Sections 4.3 and 4.4. */
class CenterRuleRasterizerTest {

    private final CenterRuleRasterizer rasterizer = new CenterRuleRasterizer();

    @Test
    void lightsPixelsWhoseCentersAreInsideTheCircle() {
        var mask = rasterizer.render(new Circle(0.5, 0.5, 0.5), IDENTITY, 4);

        assertEquals(art(
                ".##.",
                "####",
                "####",
                ".##."), render(mask));
    }

    @Test
    void countsACenterExactlyOnTheBoundaryAsInside() {
        // Pixel centers 0.375 and 0.625 lie exactly on the edges of a square of side 0.25. The circle is centered
        // on the pixel center (0.375, 0.375) with radius 0.25, so its four axis neighbours lie exactly on it.
        var square = rasterizer.render(new Square(0.5, 0.5, 0.25, 0), IDENTITY, 4);
        var circle = rasterizer.render(new Circle(0.375, 0.375, 0.25), IDENTITY, 4);

        assertAll(
                () -> assertEquals(art(
                        "....",
                        ".##.",
                        ".##.",
                        "...."), render(square)),
                () -> assertEquals(art(
                        ".#..",
                        "###.",
                        ".#..",
                        "...."), render(circle)));
    }

    @Test
    void rendersATriangleWithItsClosedEdges() {
        var mask = rasterizer.render(new Triangle(0.5, 0, 1, 1, 0, 1), IDENTITY, 4);

        assertEquals(art(
                "....",
                ".##.",
                ".##.",
                "####"), render(mask));
    }

    @Test
    void countsCentersExactlyOnEveryTriangleEdgeAsInside() {
        // covers: AC-7. The top and left edges run through the first row and column of pixel centers, and the
        // sloping edge through the centers on the anti diagonal; every edge function is exactly zero there.
        var mask = rasterizer.render(new Triangle(0.125, 0.125, 0.875, 0.125, 0.125, 0.875), IDENTITY, 4);

        assertEquals(art(
                "####",
                "###.",
                "##..",
                "#..."), render(mask));
    }

    @Test
    void countsACenterExactlyOnAHoleBoundaryAsBackground() {
        // covers: AC-7, contract Section 4.3. The hole's edges run through the four inner pixel centers (0.375 and
        // 0.625), and a hole is closed too, so all four are carved out.
        var frame = new Composite(List.of(new Square(0.5, 0.5, 1, 0)), List.of(new Square(0.5, 0.5, 0.25, 0)));

        assertEquals(art(
                "####",
                "#..#",
                "#..#",
                "####"), render(rasterizer.render(frame, IDENTITY, 4)));
    }

    @Test
    void rendersARotatedSquareAboutItsOwnCenter() {
        // A quarter turn is outside the stored period, so a diamond is tested through the pose instead.
        var diamond = rasterizer.render(new Square(0.5, 0.5, 1, 0), new Transform2D(0, 0, 0.5, Math.PI / 4), 8);

        assertEquals(art(
                "........",
                "........",
                "...##...",
                "..####..",
                "..####..",
                "...##...",
                "........",
                "........"), render(diamond));
    }

    @Test
    void aCompositeIsInsideAnyPartAndNotInsideAnyHole() {
        var ring = new Composite(List.of(new Circle(0.5, 0.5, 0.5)), List.of(new Circle(0.5, 0.5, 0.25)));
        var twoParts = new Composite(
                List.of(new Square(0.25, 0.25, 0.5, 0), new Square(0.75, 0.75, 0.5, 0)),
                List.of(new Circle(0.875, 0.875, 0.125), new Square(0, 0, 0.5, 0)));

        assertAll(
                () -> assertEquals(art(
                        ".##.",
                        "#..#",
                        "#..#",
                        ".##."), render(rasterizer.render(ring, IDENTITY, 4))),
                () -> assertEquals(art(
                        ".#..",
                        "##..",
                        "..##",
                        "..#."), render(rasterizer.render(twoParts, IDENTITY, 4))));
    }

    @Test
    void appliesThePoseThroughItsInverse() {
        var square = new Square(0.5, 0.5, 1, 0);

        assertAll(
                () -> assertEquals(art(
                        "....",
                        ".##.",
                        ".##.",
                        "...."), render(rasterizer.render(square, new Transform2D(0, 0, 0.5, 0), 4))),
                () -> assertEquals(art(
                        "....",
                        "....",
                        "..##",
                        "..##"), render(rasterizer.render(square, new Transform2D(0.25, 0.25, 0.5, 0), 4))));
    }

    @Test
    void treatsCanonicalPointsOutsideTheUnitSquareAsBackground() {
        // The circle reaches past the unit square; only the part inside the canonical frame can be lit.
        var oversized = new Circle(0.5, 0.5, 4);

        assertAll(
                () -> assertEquals(16, rasterizer.render(oversized, IDENTITY, 4).cardinality()),
                () -> assertEquals(art(
                        "....",
                        ".##.",
                        ".##.",
                        "...."), render(rasterizer.render(oversized, new Transform2D(0, 0, 0.5, 0), 4))),
                () -> assertEquals(art(
                        "..##",
                        "..##",
                        "..##",
                        "..##"), render(rasterizer.render(oversized, new Transform2D(0.5, 0, 1, 0), 4))));
    }

    @Test
    void rendersEverySupportedSizeAndThrowsOutsideIt() {
        var circle = new Circle(0.5, 0.5, 0.5);

        assertAll(
                () -> assertEquals(1, rasterizer.render(circle, IDENTITY, 1).cardinality()),
                () -> assertEquals(64, rasterizer.render(circle, IDENTITY, 64).width()),
                () -> assertThrows(IllegalArgumentException.class, () -> rasterizer.render(circle, IDENTITY, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> rasterizer.render(circle, IDENTITY, 65)));
    }

    @Test
    void twoRendersAreBitIdentical() {
        var first = rasterizer.render(VisualFixtures.DUCK, new Transform2D(0.1, -0.05, 0.75, 0.3), 64);
        var second = new CenterRuleRasterizer().render(VisualFixtures.DUCK, new Transform2D(0.1, -0.05, 0.75, 0.3), 64);

        assertArrayEquals(first.words(), second.words());
    }
}
