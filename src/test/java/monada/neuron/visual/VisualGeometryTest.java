package monada.neuron.visual;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static monada.neuron.visual.VisualFixtures.CIRCLE;
import static monada.neuron.visual.VisualFixtures.IDENTITY;
import static monada.neuron.visual.VisualFixtures.SQUARE;
import static monada.neuron.visual.VisualFixtures.T1;
import static monada.neuron.visual.VisualFixtures.circle;
import static monada.neuron.visual.VisualFixtures.g;
import static monada.neuron.visual.VisualFixtures.ok;
import static monada.neuron.visual.VisualFixtures.rejection;
import static monada.neuron.visual.VisualFixtures.square;
import static monada.neuron.visual.VisualFixtures.triangle;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 0012 AC-2, AC-4 and AC-6 for {@link VisualGeometry#canonicalize}; contract Sections 4.2 and 7. */
class VisualGeometryTest {

    @Test
    void aCircleBecomesTheUnitCircleAndItsPoseCarriesPositionAndSize() {
        var canonicalized = ok(VisualGeometry.canonicalize(new Circle(0.75, 0.25, 0.125)));

        assertAll(
                () -> assertEquals(CIRCLE, canonicalized.geometry().primitive()),
                () -> assertEquals(new Transform2D(0.25, -0.25, 0.25, 0), canonicalized.pose()));
    }

    @Test
    void aSquareBecomesTheUnitSquareAndItsPoseCarriesTheRotation() {
        var canonicalized = ok(VisualGeometry.canonicalize(new Square(1.5, 0.5, 2, 0.25)));

        assertAll(
                () -> assertEquals(SQUARE, canonicalized.geometry().primitive()),
                () -> assertEquals(new Transform2D(1, 0, 2, 0.25), canonicalized.pose()));
    }

    @Test
    void aTriangleIsTurnedSoItsLongestEdgeIsTheBottom() {
        // A 3 : 4 : 5 triangle standing on its short leg; the hypotenuse runs from (0, 0) to (0.75, 1).
        var canonicalized = ok(VisualGeometry.canonicalize(new Triangle(0, 0, 0.75, 1, 0, 1)));
        var canonical = (Triangle) canonicalized.geometry().primitive();

        assertAll(
                () -> assertEquals(canonical.y2(), canonical.y3(), "the base is horizontal"),
                () -> assertEquals(0.0, canonical.x3()),
                () -> assertEquals(1.0, canonical.x2()),
                () -> assertTrue(canonical.y1() < canonical.y2(), "the apex is above the base"),
                () -> assertEquals(1.0, canonical.y1() + canonical.y2(), "the box is centered"),
                () -> assertEquals(1.25, canonicalized.pose().scale(), 1e-12),
                () -> assertEquals(canonicalized.geometry(),
                        ok(VisualGeometry.canonicalize(canonical)).geometry()));
    }

    @Test
    void anIsoscelesTieGoesToTheSmallestSnappedVertexSequence() {
        // Two equal longest legs: both candidates are tried and the choice must not depend on argument order.
        var tall = new Triangle(0.5, 0, 0.75, 1, 0.25, 1);
        var first = ok(VisualGeometry.canonicalize(tall));
        var second = ok(VisualGeometry.canonicalize(new Triangle(0.25, 1, 0.5, 0, 0.75, 1)));
        var mirrored = ok(VisualGeometry.canonicalize(new Triangle(0.5, 1, 0.75, 0, 0.25, 0)));

        assertAll(
                () -> assertEquals(first, second),
                () -> assertEquals(first.geometry(), mirrored.geometry()));
    }

    @Test
    void anAlmostEquilateralTriangleRoundsInwardSoItsBottomEdgeStaysTheLongest() {
        // covers: AC-1, contract Section 4.8 item 1. Sixteen units wide with height 56755 / 4096: nearest snapping
        // would give apex 274 and base 3822, whose sloping edges are longer than the bottom, so both round inward.
        double height = g(56755);
        var input = new Triangle(0.5, -6, 8.5, -6 + height, -7.5, -6 + height);

        var canonicalized = ok(VisualGeometry.canonicalize(input));

        assertAll(
                () -> assertEquals(T1, canonicalized.geometry().primitive()),
                () -> assertEquals(new Transform2D(0, -6.5 + height / 2, 16, 0), canonicalized.pose()));
    }

    @Test
    void aTriangleWhoseTiedLongEdgesDoNotStayOrderedOnTheGridIsRejected() {
        // covers: AC-4. Not a needle: an isosceles with two equal long legs and a base over half their length.
        // Turned so a leg is the bottom, the other leg snaps longer, so the result is not a fixed point.
        var isosceles = triangle(3200, 96, 80, 1056, 3200, 2016);

        assertEquals(VisualRejectionReason.BELOW_GRID_RESOLUTION, rejection(VisualGeometry.canonicalize(isosceles)));
    }

    @Test
    void everyCanonicalTriangleIsAFixedPointWithTheIdentityPose() {
        int accepted = 0;
        for (long ax = -3000; ax <= 9000; ax += 1500) {
            for (long ay = -2000; ay <= 6000; ay += 1999) {
                for (long bx = 100; bx <= 8000; bx += 1317) {
                    var result = VisualGeometry.canonicalize(triangle(ax, ay, bx, 7000, 0, 6500));
                    if (result instanceof Result.Ok<Canonicalized>(Canonicalized c)) {
                        accepted++;
                        var again = ok(VisualGeometry.canonicalize(c.geometry().primitive()));
                        assertEquals(c.geometry(), again.geometry());
                        assertEquals(IDENTITY, again.pose());
                    } else {
                        assertEquals(VisualRejectionReason.BELOW_GRID_RESOLUTION, rejection(result));
                    }
                }
            }
        }
        assertTrue(accepted > 150, "most triangles canonicalize, accepted " + accepted);
    }

    @Test
    void aCompositeIsNormalizedAsAWholeOverTheBoxOfItsParts() {
        // Two circles side by side, shifted and doubled; the hole does not move the box.
        var input = new Composite(
                List.of(new Circle(1, 1.5, 0.5), new Circle(2, 1.5, 0.5)),
                List.of(new Circle(4, 1.5, 0.25)));

        var canonicalized = ok(VisualGeometry.canonicalize(input));

        assertAll(
                () -> assertEquals(new Composite(
                        List.of(new Circle(0.25, 0.5, 0.25), new Circle(0.75, 0.5, 0.25)),
                        List.of(new Circle(1.75, 0.5, 0.125))), canonicalized.geometry().primitive()),
                () -> assertEquals(new Transform2D(1, 1, 2, 0), canonicalized.pose()));
    }

    @Test
    void aCompositeIsNeverReoriented() {
        var upright = new Composite(List.of(square(2048, 2048, 2048)), List.of());
        var turned = new Composite(List.of(new Square(0.5, 0.5, 0.5, 0.25)), List.of());

        var canonicalTurned = ok(VisualGeometry.canonicalize(turned));

        assertAll(
                () -> assertEquals(0.0, canonicalTurned.pose().rotation()),
                () -> assertEquals(0.25, ((Square) ((Composite) canonicalTurned.geometry().primitive())
                        .parts().getFirst()).rotation()),
                () -> assertNotEquals(ok(VisualGeometry.canonicalize(upright)).geometry(),
                        canonicalTurned.geometry()));
    }

    @Test
    void theBoxOfATurnedSquarePartIsItsAxisAlignedExtent() {
        // covers: AC-2. A square turned by 0.25 rad reaches side / 2 · (|cos| + |sin|) from its center on each axis.
        double rotation = 0.25;
        double extent = 0.5 * (StrictMath.cos(rotation) + StrictMath.sin(rotation));

        var canonicalized = ok(VisualGeometry.canonicalize(
                new Composite(List.of(new Square(0.5, 0.5, 0.5, rotation)), List.of())));
        var part = (Square) ((Composite) canonicalized.geometry().primitive()).parts().getFirst();

        assertAll(
                () -> assertEquals(extent, canonicalized.pose().scale(), 1e-15),
                () -> assertEquals(0.0, canonicalized.pose().tx(), 1e-15),
                () -> assertEquals(0.0, canonicalized.pose().ty(), 1e-15),
                () -> assertEquals(new Square(0.5, 0.5, Math.rint(0.5 / extent * 4096) / 4096, rotation), part));
    }

    @Test
    void partsThatSnapToTheSameShapeAreKeptOnce() {
        // At scale 4 the two small circles differ by a quarter step and snap together.
        var input = new Composite(
                List.of(new Square(2, 2, 4, 0), new Circle(1, 1, 0.5), new Circle(1 + g(1), 1, 0.5)),
                List.of(new Circle(3, 3, 0.5), new Circle(3, 3 + g(1), 0.5)));

        var canonical = (Composite) ok(VisualGeometry.canonicalize(input)).geometry().primitive();

        assertAll(
                () -> assertEquals(List.of(new Circle(0.25, 0.25, 0.125), SQUARE), canonical.parts()),
                () -> assertEquals(List.of(new Circle(0.75, 0.75, 0.125)), canonical.holes()));
    }

    @Test
    void rejectsACompositeOverThePartOrHoleCap() {
        var seventeen = new ArrayList<ShapePrimitive>();
        for (int i = 0; i < 17; i++) {
            seventeen.add(circle(100 + i, 2048, 50));
        }
        var sixteen = seventeen.subList(0, 16);

        assertAll(
                () -> assertEquals(VisualRejectionReason.CAP_EXCEEDED,
                        rejection(VisualGeometry.canonicalize(new Composite(seventeen, List.of())))),
                () -> assertEquals(VisualRejectionReason.CAP_EXCEEDED,
                        rejection(VisualGeometry.canonicalize(new Composite(List.of(CIRCLE), seventeen)))),
                () -> assertEquals(16, ((Composite) ok(VisualGeometry.canonicalize(new Composite(sixteen, sixteen)))
                        .geometry().primitive()).parts().size()));
    }

    @Test
    void rejectsAPoseTranslationOutsideTheRange() {
        assertAll(
                () -> assertEquals(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE,
                        rejection(VisualGeometry.canonicalize(new Circle(2.5 + g(1), 0.5, 0.5)))),
                () -> assertEquals(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE,
                        rejection(VisualGeometry.canonicalize(new Square(0.5, -0.5 - g(1), 1, 0)))),
                () -> assertEquals(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE,
                        rejection(VisualGeometry.canonicalize(new Triangle(10.5, 0, 11, 1, 10, 1)))),
                () -> assertEquals(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE,
                        rejection(VisualGeometry.canonicalize(
                                new Composite(List.of(new Circle(0.5, 5, 0.5)), List.of())))),
                () -> assertEquals(new Transform2D(2, -1, 1, 0),
                        ok(VisualGeometry.canonicalize(new Circle(2.5, -0.5, 0.5))).pose()));
    }

    @Test
    void rejectsAShapeThatCollapsesOnTheGrid() {
        var needle = new Triangle(0.5, 0, 1, g(1), 0, g(1));
        var tinyPart = new Composite(List.of(new Square(2, 2, 4, 0), new Circle(1, 1, g(1))), List.of());
        var tinyHole = new Composite(List.of(new Square(2, 2, 4, 0)), List.of(new Square(1, 1, g(1), 0)));
        var flatHole = new Composite(List.of(new Square(2, 2, 4, 0)),
                List.of(new Triangle(1, 1, 2, 1 + g(1), 1.5, 1)));

        assertAll(
                () -> assertEquals(VisualRejectionReason.BELOW_GRID_RESOLUTION,
                        rejection(VisualGeometry.canonicalize(needle))),
                () -> assertEquals(VisualRejectionReason.BELOW_GRID_RESOLUTION,
                        rejection(VisualGeometry.canonicalize(tinyPart))),
                () -> assertEquals(VisualRejectionReason.BELOW_GRID_RESOLUTION,
                        rejection(VisualGeometry.canonicalize(tinyHole))),
                () -> assertEquals(VisualRejectionReason.BELOW_GRID_RESOLUTION,
                        rejection(VisualGeometry.canonicalize(flatHole))));
    }

    @Test
    void theFirstReasonInTheContractOrderWins() {
        var farParts = new ArrayList<ShapePrimitive>();
        for (int i = 0; i < 17; i++) {
            farParts.add(new Circle(10 + g(i), 10, g(1)));
        }
        var capThenRange = new Composite(farParts, List.of());
        var rangeThenCollapse = new Triangle(10.5, 0, 11, g(1), 10, g(1));
        var rangeThenCollapseComposite = new Composite(
                List.of(new Square(10, 10, 4, 0), new Circle(10, 10, g(1))), List.of());

        assertAll(
                () -> assertEquals(VisualRejectionReason.CAP_EXCEEDED,
                        rejection(VisualGeometry.canonicalize(capThenRange))),
                () -> assertEquals(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE,
                        rejection(VisualGeometry.canonicalize(rangeThenCollapse))),
                () -> assertEquals(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE,
                        rejection(VisualGeometry.canonicalize(rangeThenCollapseComposite))));
    }

    @Test
    void validInputAtTheCoordinateBoundIsRejectedAndNeverThrows() {
        var hugeTriangle = new Triangle(-65536, -65536, 65536, -65536, 65536, 65536);
        var hugeCircle = new Circle(65536, -65536, 65536);

        assertAll(
                () -> assertEquals(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE,
                        rejection(VisualGeometry.canonicalize(hugeTriangle))),
                () -> assertEquals(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE,
                        rejection(VisualGeometry.canonicalize(hugeCircle))));
    }

    @Test
    void aHoleBeyondTheNumeratorBoundAfterRescalingIsRejectedAndNeverThrows() {
        var farHole = new Composite(List.of(new Square(0.5, 0.5, 0.5, 0)), List.of(new Circle(60000, 0.5, 0.5)));
        var hugeHole = new Composite(List.of(new Square(0.5, 0.5, 0.5, 0)), List.of(new Square(0.5, 0.5, 60000, 0)));
        var hugeTriangleHole = new Composite(List.of(new Square(0.5, 0.5, 0.5, 0)),
                List.of(new Triangle(-60000, 0, 60000, 0, 0, 60000)));

        assertAll(
                () -> assertEquals(VisualRejectionReason.BELOW_GRID_RESOLUTION,
                        rejection(VisualGeometry.canonicalize(farHole))),
                () -> assertEquals(VisualRejectionReason.BELOW_GRID_RESOLUTION,
                        rejection(VisualGeometry.canonicalize(hugeHole))),
                () -> assertEquals(VisualRejectionReason.BELOW_GRID_RESOLUTION,
                        rejection(VisualGeometry.canonicalize(hugeTriangleHole))));
    }

    @Test
    void nullThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> VisualGeometry.canonicalize(null));
    }

    @Test
    void equalityAndHashDependOnThePrimitiveOnly() {
        var small = ok(VisualGeometry.canonicalize(new Circle(0.25, 0.25, 0.125))).geometry();
        var large = ok(VisualGeometry.canonicalize(new Circle(1, 1, 2))).geometry();

        assertAll(
                () -> assertEquals(small, large),
                () -> assertEquals(small.hashCode(), large.hashCode()),
                () -> assertEquals(CIRCLE.hashCode(), small.hashCode()),
                () -> assertNotEquals(small, ok(VisualGeometry.canonicalize(SQUARE)).geometry()),
                () -> assertNotEquals(small, CIRCLE),
                () -> assertEquals("VisualGeometry[" + CIRCLE + "]", small.toString()));
    }

    @Test
    void theWitnessMaskIsTheCanonicalPrimitiveAtSixtyFourWithTheIdentityPose() {
        var geometry = ok(VisualGeometry.canonicalize(T1)).geometry();

        assertAll(
                () -> assertEquals(64, geometry.canonicalMask().width()),
                () -> assertEquals(64, geometry.canonicalMask().height()),
                () -> assertEquals(new CenterRuleRasterizer().render(T1, IDENTITY, 64), geometry.canonicalMask()),
                () -> assertSame(geometry.canonicalMask(), geometry.canonicalMask()),
                () -> assertArrayEquals(geometry.canonicalMask().words(),
                        ok(VisualGeometry.canonicalize(T1)).geometry().canonicalMask().words()));
    }

    @Test
    void geometryHasNoPoseOrAppearanceField() {
        var fields = Arrays.stream(VisualGeometry.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getName())
                .sorted()
                .toList();

        assertEquals(List.of("canonicalMask", "primitive"), fields);
    }
}
