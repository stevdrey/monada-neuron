package monada.neuron.visual;

import org.junit.jupiter.api.Test;

import java.util.List;

import static monada.neuron.visual.VisualFixtures.T1;
import static monada.neuron.visual.VisualFixtures.g;
import static monada.neuron.visual.VisualFixtures.triangle;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Spec 0012 AC-3 and AC-6 for {@link Circle}, {@link Square} and {@link Triangle}. */
class ShapeRecordsTest {

    private static final double[] MALFORMED = {
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.45, 1e300, 65536.0 + g(1)};

    @Test
    void circleKeepsItsParameters() {
        var circle = new Circle(0.25, 0.75, 0.125);

        assertAll(
                () -> assertEquals(0.25, circle.cx()),
                () -> assertEquals(0.75, circle.cy()),
                () -> assertEquals(0.125, circle.r()));
    }

    @Test
    void circleRejectsMalformedParameters() {
        for (double bad : MALFORMED) {
            assertAll("value " + bad,
                    () -> assertThrows(IllegalArgumentException.class, () -> new Circle(bad, 0.5, 0.5)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Circle(0.5, bad, 0.5)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Circle(0.5, 0.5, bad)));
        }
        assertThrows(IllegalArgumentException.class, () -> new Circle(0.5, 0.5, 0));
        assertThrows(IllegalArgumentException.class, () -> new Circle(0.5, 0.5, -0.5));
    }

    @Test
    void squareRejectsMalformedParameters() {
        for (double bad : MALFORMED) {
            assertAll("value " + bad,
                    () -> assertThrows(IllegalArgumentException.class, () -> new Square(bad, 0.5, 1, 0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Square(0.5, bad, 1, 0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Square(0.5, 0.5, bad, 0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Square(0.5, 0.5, 1, bad)));
        }
        assertThrows(IllegalArgumentException.class, () -> new Square(0.5, 0.5, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Square(0.5, 0.5, -1, 0));
    }

    @Test
    void squareRotationStaysInsideItsSymmetryPeriod() {
        double belowQuarterTurn = g(6433);

        assertEquals(belowQuarterTurn, new Square(0.5, 0.5, 1, belowQuarterTurn).rotation());
        assertThrows(IllegalArgumentException.class, () -> new Square(0.5, 0.5, 1, g(6434)));
        assertThrows(IllegalArgumentException.class, () -> new Square(0.5, 0.5, 1, -g(1)));
        assertThrows(IllegalArgumentException.class, () -> new Square(0.5, 0.5, 1, 2));
    }

    @Test
    void negativeZeroIsStoredAsPositiveZero() {
        assertAll(
                () -> assertEquals(new Circle(0.0, 0.0, 1), new Circle(-0.0, -0.0, 1)),
                () -> assertEquals(new Circle(0.0, 0.0, 1).hashCode(), new Circle(-0.0, -0.0, 1).hashCode()),
                () -> assertEquals(new Square(0.0, 0.0, 1, 0.0), new Square(-0.0, -0.0, 1, -0.0)),
                () -> assertEquals(new Triangle(0.0, 0.0, 1, 1, 0.0, 1), new Triangle(-0.0, -0.0, 1, 1, -0.0, 1)),
                () -> assertEquals(0L, Double.doubleToRawLongBits(new Square(0.5, 0.5, 1, -0.0).rotation())));
    }

    @Test
    void triangleStoresVerticesClockwiseFromTheMinimumYThenXVertex() {
        var expected = List.of(g(2048), g(275), g(4096), g(3821), g(0), g(3821));
        long[][] vertices = {{2048, 275}, {4096, 3821}, {0, 3821}};
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

        for (int[] o : orders) {
            var t = triangle(vertices[o[0]][0], vertices[o[0]][1], vertices[o[1]][0], vertices[o[1]][1],
                    vertices[o[2]][0], vertices[o[2]][1]);
            assertAll("order " + o[0] + o[1] + o[2],
                    () -> assertEquals(expected, List.of(t.x1(), t.y1(), t.x2(), t.y2(), t.x3(), t.y3())),
                    () -> assertEquals(T1, t),
                    () -> assertEquals(T1.hashCode(), t.hashCode()));
        }
    }

    @Test
    void triangleBreaksAnEqualYStartByTheSmallerX() {
        var t = triangle(4096, 0, 2048, 4096, 0, 0);

        assertEquals(List.of(0.0, 0.0, 1.0, 0.0, 0.5, 1.0), List.of(t.x1(), t.y1(), t.x2(), t.y2(), t.x3(), t.y3()));
    }

    @Test
    void triangleRejectsMalformedParametersAndZeroArea() {
        for (double bad : MALFORMED) {
            assertAll("value " + bad,
                    () -> assertThrows(IllegalArgumentException.class, () -> new Triangle(bad, 0, 1, 1, 0, 1)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Triangle(0.5, bad, 1, 1, 0, 1)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Triangle(0.5, 0, bad, 1, 0, 1)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Triangle(0.5, 0, 1, bad, 0, 1)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Triangle(0.5, 0, 1, 1, bad, 1)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Triangle(0.5, 0, 1, 1, 0, bad)));
        }
        assertThrows(IllegalArgumentException.class, () -> new Triangle(0, 0, 0.5, 0.5, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Triangle(0, 0, 0, 0, 1, 1));
    }

    @Test
    void triangleAreaIsExactAtTheCoordinateBound() {
        var huge = new Triangle(-65536, -65536, 65536, -65536, 65536, 65536);

        assertEquals(-65536.0, huge.x1());
        assertThrows(IllegalArgumentException.class, () -> new Triangle(-65536, -65536, 0, 0, 65536, 65536));
    }

    @Test
    void differentShapesAreNotEqual() {
        assertNotEquals(new Circle(0.5, 0.5, 0.5), new Circle(0.5, 0.5, 0.25));
        assertNotEquals(T1, VisualFixtures.T2);
    }
}
