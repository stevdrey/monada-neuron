package monada.neuron.visual;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static monada.neuron.visual.VisualFixtures.CIRCLE;
import static monada.neuron.visual.VisualFixtures.DUCK;
import static monada.neuron.visual.VisualFixtures.SQUARE;
import static monada.neuron.visual.VisualFixtures.circle;
import static monada.neuron.visual.VisualFixtures.g;
import static monada.neuron.visual.VisualFixtures.square;
import static monada.neuron.visual.VisualFixtures.triangle;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Spec 0012 AC-3, AC-5 and AC-6 for {@link Composite}; canonical order of contract Section 4.2. */
class CompositeTest {

    @Test
    void sortsPartsByCenterYThenCenterXWhateverTheArgumentOrder() {
        var head = circle(2731, 1130, 894);
        var beak = triangle(3351, 1005, 4096, 1750, 3351, 1750);
        var body = circle(1489, 2371, 1489);

        var shuffled = new Composite(List.of(body, head, beak), List.of());

        assertAll(
                () -> assertEquals(List.of(head, beak, body), shuffled.parts()),
                () -> assertEquals(DUCK, shuffled),
                () -> assertEquals(DUCK.hashCode(), shuffled.hashCode()));
    }

    @Test
    void breaksCenterTiesByKindOrdinalThenByRemainingParameters() {
        var smallCircle = circle(2048, 2048, 512);
        var bigCircle = circle(2048, 2048, 1024);
        var smallSquare = square(2048, 2048, 512);
        var turnedSquare = new Square(0.5, 0.5, g(512), g(100));
        var bigSquare = square(2048, 2048, 1024);
        var lowTriangle = triangle(2048, 1024, 3072, 3072, 1024, 3072);
        var wideTriangle = triangle(2048, 1024, 4096, 3072, 0, 3072);
        var leftCircle = circle(1024, 2048, 512);

        var holes = new Composite(List.of(CIRCLE), List.of(wideTriangle, bigSquare, lowTriangle, turnedSquare,
                bigCircle, smallSquare, leftCircle, smallCircle)).holes();

        assertEquals(List.of(leftCircle, smallCircle, bigCircle, smallSquare, turnedSquare, bigSquare, lowTriangle,
                wideTriangle), holes);
    }

    @Test
    void theKindTieBreakFollowsTheSealedPermitsOrder() {
        // The canonical order ranks kinds as circle, square, triangle; this pins the declared order it mirrors.
        assertEquals(List.of(Circle.class, Square.class, Triangle.class, Composite.class),
                Arrays.asList(ShapePrimitive.class.getPermittedSubclasses()));
    }

    @Test
    void rejectsNullListsNullEntriesEmptyPartsAndNesting() {
        var nested = new Composite(List.of(CIRCLE), List.of());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new Composite(null, List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new Composite(List.of(CIRCLE), null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Composite(Arrays.asList(CIRCLE, null), List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Composite(List.of(CIRCLE), Collections.singletonList(null))),
                () -> assertThrows(IllegalArgumentException.class, () -> new Composite(List.of(), List.of(CIRCLE))),
                () -> assertThrows(IllegalArgumentException.class, () -> new Composite(List.of(nested), List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Composite(List.of(CIRCLE), List.of(nested))));
    }

    @Test
    void rejectsTheSameShapeTwiceWithinOneListOnly() {
        var sameTriangleOtherOrder = triangle(0, 3821, 2048, 275, 4096, 3821);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Composite(List.of(CIRCLE, SQUARE, circle(2048, 2048, 2048)), List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Composite(List.of(CIRCLE), List.of(VisualFixtures.T1, sameTriangleOtherOrder))),
                () -> assertEquals(List.of(CIRCLE), new Composite(List.of(CIRCLE), List.of(CIRCLE)).holes()));
    }

    @Test
    void allowsAHoleThatMissesEveryPart() {
        var composite = new Composite(List.of(circle(1024, 1024, 512)), List.of(circle(3072, 3072, 512)));

        assertEquals(1, composite.holes().size());
    }

    @Test
    void doesNotEnforceTheCapsAtConstruction() {
        var many = new ArrayList<ShapePrimitive>();
        for (int i = 0; i < 17; i++) {
            many.add(circle(100 + i, 2048, 50));
        }

        var composite = new Composite(many, many);

        assertAll(
                () -> assertEquals(17, composite.parts().size()),
                () -> assertEquals(17, composite.holes().size()));
    }

    @Test
    void copiesItsListsAndExposesThemUnmodifiable() {
        var parts = new ArrayList<ShapePrimitive>(List.of(CIRCLE));
        var holes = new ArrayList<ShapePrimitive>(List.of(circle(2048, 2048, 614)));
        var composite = new Composite(parts, holes);

        parts.add(SQUARE);
        holes.clear();

        assertAll(
                () -> assertEquals(VisualFixtures.RING, composite),
                () -> assertThrows(UnsupportedOperationException.class, () -> composite.parts().add(SQUARE)),
                () -> assertThrows(UnsupportedOperationException.class, () -> composite.holes().clear()));
    }
}
