package monada.neuron.visual;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static monada.neuron.visual.VisualFixtures.CIRCLE;
import static monada.neuron.visual.VisualFixtures.DUCK;
import static monada.neuron.visual.VisualFixtures.IDENTITY;
import static monada.neuron.visual.VisualFixtures.L_SHAPE;
import static monada.neuron.visual.VisualFixtures.NOTCH;
import static monada.neuron.visual.VisualFixtures.RING;
import static monada.neuron.visual.VisualFixtures.SQUARE;
import static monada.neuron.visual.VisualFixtures.SQUARE_FRAME;
import static monada.neuron.visual.VisualFixtures.T1;
import static monada.neuron.visual.VisualFixtures.T1_FRAME;
import static monada.neuron.visual.VisualFixtures.T2;
import static monada.neuron.visual.VisualFixtures.T3;
import static monada.neuron.visual.VisualFixtures.TWO_CIRCLES;
import static monada.neuron.visual.VisualFixtures.g;
import static monada.neuron.visual.VisualFixtures.ok;
import static monada.neuron.visual.VisualFixtures.rejection;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Contract conformance for issue #143 (spec 0012 AC-1, AC-2 and AC-6): the gold shapes of contract
 * {@code visual-geometry/1} Sections 4.8 and 8.5 are canonical, their witness masks have the stated topology, and a
 * grid exact posed copy canonicalizes to the same geometry with the pose that was applied.
 */
class VisualGeometryConformanceTest {

    private static final Map<String, ShapePrimitive> GOLD = gold();
    private static final double[][] TRANSLATIONS = {{0, 0}, {g(1024), g(-512)}, {g(-1), g(4095)}, {-1, 2}};
    private static final double[] SCALES = {1, 2, 4};

    private static Map<String, ShapePrimitive> gold() {
        var shapes = new LinkedHashMap<String, ShapePrimitive>();
        shapes.put("circle", CIRCLE);
        shapes.put("square", SQUARE);
        shapes.put("T1", T1);
        shapes.put("T2", T2);
        shapes.put("T3", T3);
        shapes.put("m-cmp-01 duck", DUCK);
        shapes.put("m-cmp-05 ring", RING);
        shapes.put("m-cmp-08 square frame", SQUARE_FRAME);
        shapes.put("m-cmp-10 two circles", TWO_CIRCLES);
        shapes.put("m-cmp-13 T1 frame", T1_FRAME);
        shapes.put("m-cmp-17 notch", NOTCH);
        shapes.put("m-cmp-19 L shape", L_SHAPE);
        return shapes;
    }

    @Test
    void everyGoldShapeIsCanonicalWithTheIdentityPose() {
        GOLD.forEach((id, shape) -> {
            var canonicalized = ok(VisualGeometry.canonicalize(shape));
            assertAll(id,
                    () -> assertEquals(shape, canonicalized.geometry().primitive()),
                    () -> assertEquals(IDENTITY, canonicalized.pose()));
        });
    }

    @Test
    void witnessMasksHaveTheStatedComponentsAndHoles() {
        var expected = new LinkedHashMap<String, MaskTopology>();
        expected.put("circle", new MaskTopology(1, 0));
        expected.put("square", new MaskTopology(1, 0));
        expected.put("T1", new MaskTopology(1, 0));
        expected.put("T2", new MaskTopology(1, 0));
        expected.put("T3", new MaskTopology(1, 0));
        expected.put("m-cmp-01 duck", new MaskTopology(1, 0));
        expected.put("m-cmp-05 ring", new MaskTopology(1, 1));
        expected.put("m-cmp-08 square frame", new MaskTopology(1, 1));
        expected.put("m-cmp-10 two circles", new MaskTopology(2, 0));
        expected.put("m-cmp-13 T1 frame", new MaskTopology(1, 1));
        expected.put("m-cmp-17 notch", new MaskTopology(1, 0));
        expected.put("m-cmp-19 L shape", new MaskTopology(1, 0));

        var actual = new LinkedHashMap<String, MaskTopology>();
        GOLD.forEach((id, shape) -> actual.put(id,
                MaskTopology.of(ok(VisualGeometry.canonicalize(shape)).geometry().canonicalMask())));

        assertEquals(expected, actual);
    }

    @Test
    void theFloodFillHelperSeparatesDiagonalForegroundFromEnclosedBackground() {
        var mask = MaskArt.parse(
                "#....",
                ".#.##",
                "...#.",
                "####.",
                "#.#..",
                "###.#");

        // The top left pair touches only diagonally and is one component; so does the right hand group. The single
        // enclosed background pixel is the one hole; the other background reaches the border.
        assertEquals(new MaskTopology(3, 1), MaskTopology.of(mask));
    }

    @Test
    void translatedAndScaledCopiesCanonicalizeToTheSameGeometry() {
        GOLD.forEach((id, shape) -> {
            var unposed = ok(VisualGeometry.canonicalize(shape)).geometry();
            for (double scale : SCALES) {
                for (double[] t : TRANSLATIONS) {
                    var pose = new Transform2D(t[0], t[1], scale, 0);
                    var canonicalized = ok(VisualGeometry.canonicalize(posed(shape, pose)));
                    assertAll(id + " " + pose,
                            () -> assertEquals(unposed, canonicalized.geometry()),
                            () -> assertEquals(unposed.canonicalMask(), canonicalized.geometry().canonicalMask()),
                            () -> assertEquals(pose, canonicalized.pose()));
                }
            }
        });
    }

    @Test
    void trianglesTurnedByQuarterAndHalfTurnsCanonicalizeToTheSameGeometry() {
        double[] turns = {Math.PI / 2, Math.PI};
        // Not the range corner (-1, 2): a turned triangle's translation is R(θ)·m − c in double, so at the exact
        // bound it can land one ulp outside and be TRANSFORM_OUT_OF_RANGE (pinned by the test below).
        double[][] translations = {{0, 0}, {g(1024), g(-512)}, {g(-1), g(4095)}, {g(-4000), g(8000)}};
        for (Triangle triangle : List.of(T1, T2, T3)) {
            for (double turn : turns) {
                for (double scale : SCALES) {
                    for (double[] t : translations) {
                        var pose = new Transform2D(t[0], t[1], scale, turn);
                        var input = (Triangle) posed(triangle, pose);
                        var canonicalized = ok(VisualGeometry.canonicalize(input));
                        assertAll(triangle + " " + pose,
                                () -> assertEquals(triangle, canonicalized.geometry().primitive()),
                                () -> assertEquals(turn, canonicalized.pose().rotation()),
                                () -> assertEquals(scale, canonicalized.pose().scale(), 1e-12),
                                () -> assertEquals(t[0], canonicalized.pose().tx(), 1e-12),
                                () -> assertEquals(t[1], canonicalized.pose().ty(), 1e-12),
                                () -> assertPoseMapsOnto(vertices(triangle), posedVertices(triangle, pose),
                                        canonicalized.pose()));
                    }
                }
            }
        }
    }

    @Test
    void theCatalogueEquilateralTurnedAQuarterReturnsItselfWithThatRotation() {
        // The regression of the spec cross check: ranking base candidates on snapped lengths picked an apex at
        // x = 2047 here.
        var turned = (Triangle) posed(T1, new Transform2D(0, 0, 1, Math.PI / 2));

        var canonicalized = ok(VisualGeometry.canonicalize(turned));

        assertAll(
                () -> assertEquals(T1, canonicalized.geometry().primitive()),
                () -> assertEquals(new Transform2D(0, 0, 1, Math.PI / 2).rotation(), canonicalized.pose().rotation()));
    }

    @Test
    void squaresTurnedInsideTheirPeriodKeepTheRotationOnThePose() {
        double[] rotations = {g(1), g(1024), g(3217), g(6433)};
        for (double rotation : rotations) {
            for (double scale : SCALES) {
                for (double[] t : TRANSLATIONS) {
                    var input = new Square(0.5 + t[0], 0.5 + t[1], scale, rotation);
                    var canonicalized = ok(VisualGeometry.canonicalize(input));
                    var expectedPose = new Transform2D(t[0], t[1], scale, rotation);
                    assertAll(input.toString(),
                            () -> assertEquals(SQUARE, canonicalized.geometry().primitive()),
                            () -> assertEquals(expectedPose, canonicalized.pose()),
                            () -> assertPoseMapsOnto(new double[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}, {0.5, 0.5}},
                                    corners(input), canonicalized.pose()));
                }
            }
        }
    }

    @Test
    void posesMapCanonicalCentersAndVerticesOntoThePosedInput() {
        GOLD.forEach((id, shape) -> {
            for (double scale : SCALES) {
                for (double[] t : TRANSLATIONS) {
                    var input = posed(shape, new Transform2D(t[0], t[1], scale, 0));
                    var canonicalized = ok(VisualGeometry.canonicalize(input));
                    assertPoseMapsOnto(points(canonicalized.geometry().primitive()), points(input),
                            canonicalized.pose());
                }
            }
        });
    }

    @Test
    void witnessMasksKeepTheirPinnedCardinalityAndWordHash() {
        // covers: AC-6. Pinned from the Section 4.4 reference rasterizer on 2026-11-21. A change here means the
        // arithmetic changed, which breaks the bit for bit hashes that #142 and #146 must match.
        var pinned = new LinkedHashMap<String, String>();
        pinned.put("circle", "3228 413129c1");
        pinned.put("square", "4096 4dbf7801");
        pinned.put("T1", "1792 8e2634e1");
        pinned.put("T2", "1056 3f9ab156");
        pinned.put("T3", "965 7609b11a");
        pinned.put("m-cmp-01 duck", "2184 d8bb0083");
        pinned.put("m-cmp-05 ring", "2944 e06b4241");
        pinned.put("m-cmp-08 square frame", "3072 c7eb3601");
        pinned.put("m-cmp-10 two circles", "1432 f0c70bc1");
        pinned.put("m-cmp-13 T1 frame", "1638 aea74e10");
        pinned.put("m-cmp-17 notch", "4060 3152caa1");
        pinned.put("m-cmp-19 L shape", "2071 f57236e2");
        var actual = new LinkedHashMap<String, String>();
        GOLD.forEach((id, shape) -> {
            var mask = ok(VisualGeometry.canonicalize(shape)).geometry().canonicalMask();
            actual.put(id, mask.cardinality() + " " + Integer.toHexString(Arrays.hashCode(mask.words())));
        });

        assertEquals(pinned.toString().replace(", ", "\n"), actual.toString().replace(", ", "\n"));
    }

    @Test
    void aTurnedTriangleExactlyOnTheTranslationBoundIsRejectedByOneUlp() {
        // covers: AC-4, contract Section 7. The pose translation is R(θ)·m − c in double, and sin(π) is 1.2e-16
        // instead of 0, so the exact value 2 comes out one ulp above the bound.
        var pose = new Transform2D(2, 2, 4, Math.PI);

        assertAll(
                () -> assertEquals(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE,
                        rejection(VisualGeometry.canonicalize(posed(T2, pose)))),
                () -> assertEquals(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE,
                        rejection(VisualGeometry.canonicalize(posed(T3, pose)))));
    }

    @Test
    void twoRunsGiveBitIdenticalWitnessMasks() {
        GOLD.forEach((id, shape) -> assertArrayEquals(
                ok(VisualGeometry.canonicalize(shape)).geometry().canonicalMask().words(),
                ok(VisualGeometry.canonicalize(shape)).geometry().canonicalMask().words(), id));
    }

    @Test
    void noGeometryTypeCarriesANameLabelPositionOrAppearance() {
        var components = new LinkedHashMap<String, List<String>>();
        for (Class<?> type : List.of(Circle.class, Square.class, Triangle.class, Composite.class)) {
            components.put(type.getSimpleName(),
                    Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList());
        }

        assertAll(
                () -> assertEquals(Map.of(
                        "Circle", List.of("cx", "cy", "r"),
                        "Square", List.of("cx", "cy", "side", "rotation"),
                        "Triangle", List.of("x1", "y1", "x2", "y2", "x3", "y3"),
                        "Composite", List.of("parts", "holes")), components),
                () -> assertEquals(0, ShapePrimitive.class.getDeclaredMethods().length),
                () -> assertFalse(Arrays.stream(VisualGeometry.class.getMethods())
                        .anyMatch(method -> List.of("name", "label", "kind", "pose", "color").contains(method.getName()))));
    }

    /** The shape with every center and vertex mapped by {@code pose}; a rotation applies to triangles only. */
    private static ShapePrimitive posed(ShapePrimitive shape, Transform2D pose) {
        return switch (shape) {
            case Circle c -> {
                double[] center = pose.apply(c.cx(), c.cy());
                yield new Circle(center[0], center[1], c.r() * pose.scale());
            }
            case Square s -> {
                double[] center = pose.apply(s.cx(), s.cy());
                yield new Square(center[0], center[1], s.side() * pose.scale(), s.rotation());
            }
            case Triangle t -> {
                double[] a = exact(pose, t.x1(), t.y1());
                double[] b = exact(pose, t.x2(), t.y2());
                double[] c = exact(pose, t.x3(), t.y3());
                yield new Triangle(a[0], a[1], b[0], b[1], c[0], c[1]);
            }
            case Composite composite -> new Composite(
                    composite.parts().stream().map(part -> posed(part, pose)).toList(),
                    composite.holes().stream().map(hole -> posed(hole, pose)).toList());
        };
    }

    /** A grid exact quarter turn multiple, computed without trigonometry so the posed input is exactly on grid. */
    private static double[] exact(Transform2D pose, double x, double y) {
        double dx = pose.scale() * (x - 0.5);
        double dy = pose.scale() * (y - 0.5);
        double rotation = pose.rotation();
        double rx = rotation == 0 ? dx : rotation == Math.PI / 2 ? -dy : -dx;
        double ry = rotation == 0 ? dy : rotation == Math.PI / 2 ? dx : -dy;
        return new double[] {rx + 0.5 + pose.tx(), ry + 0.5 + pose.ty()};
    }

    private static double[][] points(ShapePrimitive shape) {
        return switch (shape) {
            case Circle c -> new double[][] {{c.cx(), c.cy()}};
            case Square s -> corners(s);
            case Triangle t -> vertices(t);
            case Composite composite -> {
                var all = new ArrayList<double[]>();
                composite.parts().forEach(part -> all.addAll(Arrays.asList(points(part))));
                composite.holes().forEach(hole -> all.addAll(Arrays.asList(points(hole))));
                yield all.toArray(double[][]::new);
            }
        };
    }

    private static double[][] vertices(Triangle t) {
        return new double[][] {{t.x1(), t.y1()}, {t.x2(), t.y2()}, {t.x3(), t.y3()}};
    }

    /** The posed vertices in the canonical triangle's own order; the posed record stores them reordered. */
    private static double[][] posedVertices(Triangle t, Transform2D pose) {
        return new double[][] {exact(pose, t.x1(), t.y1()), exact(pose, t.x2(), t.y2()), exact(pose, t.x3(), t.y3())};
    }

    private static double[][] corners(Square s) {
        double cos = StrictMath.cos(s.rotation());
        double sin = StrictMath.sin(s.rotation());
        double h = s.side() / 2;
        double[][] offsets = {{-h, -h}, {h, -h}, {h, h}, {-h, h}, {0, 0}};
        double[][] corners = new double[5][];
        for (int i = 0; i < 5; i++) {
            corners[i] = new double[] {s.cx() + offsets[i][0] * cos - offsets[i][1] * sin,
                    s.cy() + offsets[i][0] * sin + offsets[i][1] * cos};
        }
        return corners;
    }

    private static void assertPoseMapsOnto(double[][] canonical, double[][] input, Transform2D pose) {
        double tolerance = pose.scale() / 4096 + 1e-12;
        assertEquals(canonical.length, input.length);
        for (int i = 0; i < canonical.length; i++) {
            assertArrayEquals(input[i], pose.apply(canonical[i][0], canonical[i][1]), tolerance,
                    "point " + i + " under " + pose);
        }
    }
}
