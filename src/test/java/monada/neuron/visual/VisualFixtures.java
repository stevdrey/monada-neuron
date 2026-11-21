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
