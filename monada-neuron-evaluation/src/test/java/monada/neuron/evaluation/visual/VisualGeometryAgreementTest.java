// ABOUTME: Checks that every gold shape of the visual fixture corpus is canonical in monada.neuron.visual.
// ABOUTME: Compares its 64x64 witness mask bit for bit with this module's own render (spec 0013, AC-10).
package monada.neuron.evaluation.visual;

import monada.neuron.visual.BinaryMask;
import monada.neuron.visual.Canonicalized;
import monada.neuron.visual.Circle;
import monada.neuron.visual.Composite;
import monada.neuron.visual.Result;
import monada.neuron.visual.ShapePrimitive;
import monada.neuron.visual.Square;
import monada.neuron.visual.Transform2D;
import monada.neuron.visual.Triangle;
import monada.neuron.visual.VisualGeometry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agreement between the two independent implementations of contract Section 4.4 (spec 0013, AC-10): every gold shape
 * of the corpus is accepted by the constructors of {@code monada.neuron.visual}, is already canonical there, and its
 * 64x64 witness mask equals this module's own render of it at the identity transform, bit for bit. Only this test
 * names a type of {@code monada.neuron.visual}; the generator itself uses none.
 */
class VisualGeometryAgreementTest {

    private static final int WITNESS_SIZE = 64;
    private static final double GRID = 4096.0;

    private final FixtureCatalogue catalogue = new FixtureCatalogue();
    private final MaskRenderer masks = new MaskRenderer();

    /** Every distinct {@code gold_geometry} value and every {@code gold_layers} geometry, in catalogue order. */
    private Set<GoldShape> goldShapes() {
        var shapes = new LinkedHashSet<GoldShape>();
        for (FixtureDefinition definition : catalogue.definitions()) {
            definition.shape().ifPresent(shapes::add);
            definition.layers().forEach(layer -> shapes.add(layer.geometry()));
        }
        return shapes;
    }

    private ShapePrimitive primitive(GoldShape shape) {
        return switch (shape) {
            case GoldShape.Circle c -> new Circle(c.cx() / GRID, c.cy() / GRID, c.r() / GRID);
            case GoldShape.Square s -> new Square(s.cx() / GRID, s.cy() / GRID, s.side() / GRID,
                    s.rotation() / GRID);
            case GoldShape.Triangle t -> new Triangle(t.x1() / GRID, t.y1() / GRID, t.x2() / GRID, t.y2() / GRID,
                    t.x3() / GRID, t.y3() / GRID);
            case GoldShape.Composite c -> new Composite(c.parts().stream().map(this::primitive).toList(),
                    c.holes().stream().map(this::primitive).toList());
        };
    }

    @Test
    void theCorpusHasTheExpectedNumberOfDistinctGoldShapes() {
        // Circle, square, T1, T2, T3; the duck and 20 other composites; the two overlapping circle pairs.
        assertEquals(5 + 21 + 2, goldShapes().size());
    }

    @Test
    void everyGoldShapeIsAlreadyCanonicalAndRendersToTheSameWitnessMask() {
        for (GoldShape shape : goldShapes()) {
            ShapePrimitive primitive = primitive(shape);

            Result<Canonicalized> result = VisualGeometry.canonicalize(primitive);

            var ok = assertInstanceOf(Result.Ok.class, result, shape.print());
            var canonicalized = (Canonicalized) ok.value();
            assertEquals(primitive, canonicalized.geometry().primitive(), shape.print());
            assertEquals(new Transform2D(0, 0, 1, 0), canonicalized.pose(), shape.print());

            BinaryMask witness = canonicalized.geometry().canonicalMask();
            byte[] own = masks.renderShape(shape, new GoldTransform(0.0, 0.0, 1.0, 0.0), WITNESS_SIZE);
            assertEquals(WITNESS_SIZE, witness.width());
            assertEquals(WITNESS_SIZE, witness.height());
            for (var y = 0; y < WITNESS_SIZE; y++) {
                for (var x = 0; x < WITNESS_SIZE; x++) {
                    assertEquals(own[y * WITNESS_SIZE + x] != 0, witness.get(x, y),
                            shape.print() + " at (" + x + ", " + y + ")");
                }
            }
        }
    }

    @Test
    void theSliverTrianglesAreNotGoldShapes() {
        List<String> printed = goldShapes().stream().map(GoldShape::print).toList();

        assertTrue(printed.stream().noneMatch(shape -> shape.contains("1984") || shape.contains("2016")), "m-neg-14");
    }
}
