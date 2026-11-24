package monada.neuron.visual;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundingBoxTest {

    @Test
    void aCircleBoxIsItsCenterPlusAndMinusTheRadius() {
        assertEquals(new BoundingBox(0.25, 0.75, 0.0, 0.5), BoundingBox.of(new Circle(0.5, 0.25, 0.25)));
    }

    @Test
    void anUnturnedSquareBoxIsItsOwnOutline() {
        assertEquals(new BoundingBox(0.25, 0.75, 0.25, 0.75), BoundingBox.of(new Square(0.5, 0.5, 0.5, 0)));
    }

    @Test
    void aTurnedSquareBoxGrowsWithTheRotation() {
        BoundingBox box = BoundingBox.of(new Square(0.5, 0.5, 0.5, 0.5));

        double half = 0.25 * (StrictMath.cos(0.5) + StrictMath.sin(0.5));
        assertEquals(new BoundingBox(0.5 - half, 0.5 + half, 0.5 - half, 0.5 + half), box);
    }

    @Test
    void aTriangleBoxSpansItsVerticesAndItsCenterIsTheOrderKey() {
        BoundingBox box = BoundingBox.of(new Triangle(0.25, 0.0, 1.0, 0.5, 0.0, 0.75));

        assertEquals(new BoundingBox(0.0, 1.0, 0.0, 0.75), box);
        assertEquals(0.5, box.centerX());
        assertEquals(0.375, box.centerY());
        assertEquals(1.0, box.extent());
    }

    @Test
    void aUnionCoversBothBoxes() {
        BoundingBox union = new BoundingBox(0.0, 0.5, 0.25, 0.5).union(new BoundingBox(0.25, 1.0, 0.0, 0.375));

        assertEquals(new BoundingBox(0.0, 1.0, 0.0, 0.5), union);
    }

    @Test
    void aCompositeHasNoBoxOfItsOwn() {
        Composite composite = new Composite(List.of(new Circle(0.5, 0.5, 0.5)), List.of());

        assertThrows(IllegalArgumentException.class, () -> BoundingBox.of(composite));
    }
}
