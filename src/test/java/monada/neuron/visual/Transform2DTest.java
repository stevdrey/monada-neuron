package monada.neuron.visual;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Spec 0012 AC-3 and AC-8; contract Sections 4.1 and 4.7. */
class Transform2DTest {

    @Test
    void identityLeavesPointsInPlace() {
        assertArrayEquals(new double[] {0.25, 0.875}, VisualFixtures.IDENTITY.apply(0.25, 0.875));
        assertArrayEquals(new double[] {0.25, 0.875}, VisualFixtures.IDENTITY.applyInverse(0.25, 0.875));
    }

    @Test
    void scalesAboutTheCenterThenTranslates() {
        var pose = new Transform2D(0.25, -0.125, 2, 0);

        assertArrayEquals(new double[] {0.75, 0.375}, pose.apply(0.5, 0.5));
        assertArrayEquals(new double[] {-0.25, -0.625}, pose.apply(0, 0));
    }

    @Test
    void positiveRotationIsClockwiseOnScreen() {
        var quarterTurn = new Transform2D(0, 0, 1, Math.PI / 2);

        // The point right of the center moves below it: y grows downward.
        assertArrayEquals(new double[] {0.5, 1.0}, quarterTurn.apply(1.0, 0.5), 1e-15);
    }

    @Test
    void applyInverseUndoesApplyAcrossTheContractPoseRange() {
        double[] scales = {0.1875, 0.25, 0.375, 0.675, 0.75, 1, 2, 4};
        double[] rotations = {-3.14159, -Math.PI / 2, -1, 0, 0.5, Math.PI / 2, 2.5, Math.PI};
        double[][] translations = {{0, 0}, {1, 0}, {-1, 2}, {2, -1}, {0.3, -0.7}};
        double[][] points = {{0, 0}, {1, 1}, {0.5, 0.5}, {0.36, 0.26}, {-3, 7}};

        for (double scale : scales) {
            double tolerance = 1e-12 * Math.max(1, 1 / scale);
            for (double rotation : rotations) {
                for (double[] t : translations) {
                    var pose = new Transform2D(t[0], t[1], scale, rotation);
                    for (double[] p : points) {
                        double[] posed = pose.apply(p[0], p[1]);
                        double[] back = pose.applyInverse(posed[0], posed[1]);
                        double[] canonical = pose.applyInverse(p[0], p[1]);
                        double[] forward = pose.apply(canonical[0], canonical[1]);
                        assertAll(pose + " at " + p[0] + ", " + p[1],
                                () -> assertArrayEquals(p, back, tolerance),
                                () -> assertArrayEquals(p, forward, tolerance));
                    }
                }
            }
        }
    }

    @Test
    void invertsThePoseWhoseInverseTranslationLeavesTheRange() {
        var pose = new Transform2D(1, 0, 0.25, 0);

        // As a Transform2D the inverse would need tx -4, which is why applyInverse is a point map.
        assertArrayEquals(new double[] {-3.5, 0.5}, pose.applyInverse(0.5, 0.5));
        assertArrayEquals(new double[] {0.5, 0.5}, pose.apply(-3.5, 0.5));
    }

    @Test
    void rejectsMalformedValues() {
        double[] nonfinite = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double bad : nonfinite) {
            assertAll("value " + bad,
                    () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(bad, 0, 1, 0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(0, bad, 1, 0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(0, 0, bad, 0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(0, 0, 1, bad)));
        }
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(0, 0, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(0, 0, -1, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(0, 0, 1, -Math.PI)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(0, 0, 1, Math.nextUp(Math.PI))),
                () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(Math.nextDown(-1.0), 0, 1, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(Math.nextUp(2.0), 0, 1, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(0, Math.nextDown(-1.0), 1, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Transform2D(0, Math.nextUp(2.0), 1, 0)));
    }

    @Test
    void acceptsTheClosedBoundsAndValuesOffTheGrid() {
        var pose = new Transform2D(-1, 2, 0.1, Math.PI);

        assertAll(
                () -> assertEquals(-1.0, pose.tx()),
                () -> assertEquals(2.0, pose.ty()),
                () -> assertEquals(0.1, pose.scale()),
                () -> assertEquals(Math.PI, pose.rotation()));
    }

    @Test
    void negativeZeroIsStoredAsPositiveZero() {
        assertEquals(new Transform2D(0.0, 0.0, 1, 0.0), new Transform2D(-0.0, -0.0, 1, -0.0));
        assertEquals(new Transform2D(0.0, 0.0, 1, 0.0).hashCode(), new Transform2D(-0.0, -0.0, 1, -0.0).hashCode());
    }
}
