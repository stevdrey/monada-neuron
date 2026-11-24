package monada.neuron.visual;

import java.util.List;

/** Gold shapes of contract {@code visual-geometry/1} (Sections 4.8 and 8.5) written as grid numerators. */
final class VisualFixtures {

    static final Transform2D IDENTITY = new Transform2D(0, 0, 1, 0);

    static final Circle CIRCLE = circle(2048, 2048, 2048);
    static final Square SQUARE = square(2048, 2048, 4096);
    static final Triangle T1 = triangle(2048, 275, 4096, 3821, 0, 3821);
    static final Triangle T2 = triangle(2048, 1024, 4096, 3072, 0, 3072);
    static final Triangle T3 = triangle(1475, 1065, 4096, 3031, 0, 3031);

    static final Composite DUCK = new Composite(List.of(
            circle(2731, 1130, 894), triangle(3351, 1005, 4096, 1750, 3351, 1750), circle(1489, 2371, 1489)),
            List.of());
    static final Composite RING = new Composite(List.of(CIRCLE), List.of(circle(2048, 2048, 614)));
    static final Composite SQUARE_FRAME = new Composite(List.of(SQUARE), List.of(square(2048, 2048, 2048)));
    static final Composite TWO_CIRCLES = new Composite(
            List.of(circle(964, 2048, 964), circle(3132, 2048, 964)), List.of());
    static final Composite T1_FRAME = new Composite(
            List.of(T1), List.of(triangle(2048, 1516, 2662, 2580, 1434, 2580)));
    static final Composite NOTCH = new Composite(List.of(SQUARE), List.of(square(2048, 205, 410)));
    static final Composite L_SHAPE = new Composite(List.of(SQUARE), List.of(square(2662, 1434, 2867)));

    // The other composite variants of Section 8.5, named by their fixture id.
    static final Composite RING_06 = new Composite(List.of(CIRCLE), List.of(circle(2048, 2048, 1229)));
    static final Composite RING_07 = new Composite(List.of(CIRCLE), List.of(circle(2048, 2048, 1843)));
    static final Composite SQUARE_FRAME_09 = new Composite(List.of(SQUARE), List.of(square(2048, 2048, 3686)));
    static final Composite TWO_CIRCLES_11 = new Composite(
            List.of(circle(819, 2048, 819), circle(3277, 2048, 819)), List.of());
    static final Composite TWO_CIRCLES_12 = new Composite(
            List.of(circle(683, 2048, 683), circle(3413, 2048, 683)), List.of());
    static final Composite T1_FRAME_14 = new Composite(
            List.of(T1), List.of(triangle(2048, 984, 3277, 3112, 819, 3112)));
    static final Composite SNOWMAN_15 = new Composite(
            List.of(circle(2048, 396, 396), circle(2048, 1453, 661), circle(2048, 3105, 991)), List.of());
    static final Composite SNOWMAN_16 = new Composite(
            List.of(circle(2048, 367, 367), circle(2048, 1498, 611), circle(2048, 3179, 917)), List.of());
    static final Composite NOTCH_18 = new Composite(List.of(SQUARE), List.of(square(2048, 614, 1229)));
    static final Composite L_SHAPE_20 = new Composite(List.of(SQUARE), List.of(square(3072, 1024, 2048)));
    static final Composite DUMBBELL_21 = new Composite(
            List.of(circle(819, 2048, 819), square(2048, 2048, 819), circle(3277, 2048, 819)), List.of());
    static final Composite DUMBBELL_22 = new Composite(
            List.of(circle(683, 2048, 683), square(2048, 2048, 1365), circle(3413, 2048, 683)), List.of());
    static final Composite TOUCHING_SQUARES_23 = new Composite(
            List.of(square(1024, 2048, 2048), square(3072, 2048, 2048)), List.of());
    static final Composite TOUCHING_SQUARES_24 = new Composite(
            List.of(square(1280, 2048, 2560), square(3328, 2048, 1536)), List.of());

    private VisualFixtures() {
    }

    static double g(long numerator) {
        return numerator / 4096.0;
    }

    static Circle circle(long cx, long cy, long r) {
        return new Circle(g(cx), g(cy), g(r));
    }

    static Square square(long cx, long cy, long side) {
        return new Square(g(cx), g(cy), g(side), 0);
    }

    static Triangle triangle(long x1, long y1, long x2, long y2, long x3, long y3) {
        return new Triangle(g(x1), g(y1), g(x2), g(y2), g(x3), g(y3));
    }

    static <T> T ok(Result<T> result) {
        if (result instanceof Result.Ok<T>(T value)) {
            return value;
        }
        throw new AssertionError("expected Ok, was " + result);
    }

    static VisualRejectionReason rejection(Result<?> result) {
        if (result instanceof Result.Rejected<?> rejected) {
            return rejected.rejection().reason();
        }
        throw new AssertionError("expected Rejected, was " + result);
    }
}
