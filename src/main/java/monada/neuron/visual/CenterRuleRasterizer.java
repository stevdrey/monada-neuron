package monada.neuron.visual;

import java.util.List;

/**
 * The single implementation of the render arithmetic of contract {@code visual-geometry/1} Section 4.4.
 *
 * <p>A pixel is foreground when its center, mapped through the inverse pose, is inside the closed canonical shape;
 * a boundary point counts as inside, and a canonical point outside the unit square is background. The arithmetic is
 * the contract's sequence in {@code double}, left to right, with {@code StrictMath} trigonometry and no fused
 * multiply add, so every renderer that wraps this class gives the same pixels. Single threaded and allocation free
 * per pixel.
 */
final class CenterRuleRasterizer {

    /**
     * Renders {@code primitive} posed by {@code pose} into an {@code n x n} mask.
     *
     * @throws IllegalArgumentException when {@code n} is outside {@code [1, 64]}; callers in this package pass a
     *                                  checked size, and a public wrapper returns a typed rejection instead
     */
    BinaryMask render(ShapePrimitive primitive, Transform2D pose, int n) {
        if (n < 1 || n > BinaryMask.MAX_SIZE) {
            throw new IllegalArgumentException("n must be in [1, 64], was " + n);
        }
        PointTest shape = compile(primitive);
        double cos = StrictMath.cos(pose.rotation());
        double sin = StrictMath.sin(pose.rotation());
        double tx = pose.tx();
        double ty = pose.ty();
        double scale = pose.scale();
        long[] words = new long[(n * n + 63) >>> 6];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double qx = (x + 0.5) / n - Transform2D.CENTER - tx;
                double qy = (y + 0.5) / n - Transform2D.CENTER - ty;
                double u = (qx * cos + qy * sin) / scale + Transform2D.CENTER;
                double v = (-qx * sin + qy * cos) / scale + Transform2D.CENTER;
                if (u >= 0 && u <= 1 && v >= 0 && v <= 1 && shape.contains(u, v)) {
                    int bit = y * n + x;
                    words[bit >>> 6] |= 1L << bit;
                }
            }
        }
        return new BinaryMask(n, n, words);
    }

    private PointTest compile(ShapePrimitive primitive) {
        return switch (primitive) {
            case Circle circle -> compile(circle);
            case Square square -> compile(square);
            case Triangle triangle -> compile(triangle);
            case Composite composite -> {
                PointTest[] parts = compileAll(composite.parts());
                PointTest[] holes = compileAll(composite.holes());
                yield (u, v) -> containsAny(parts, u, v) && !containsAny(holes, u, v);
            }
        };
    }

    private PointTest[] compileAll(List<ShapePrimitive> shapes) {
        PointTest[] tests = new PointTest[shapes.size()];
        for (int i = 0; i < tests.length; i++) {
            tests[i] = compile(shapes.get(i));
        }
        return tests;
    }

    private boolean containsAny(PointTest[] tests, double u, double v) {
        for (PointTest test : tests) {
            if (test.contains(u, v)) {
                return true;
            }
        }
        return false;
    }

    private PointTest compile(Circle circle) {
        double cx = circle.cx();
        double cy = circle.cy();
        double r = circle.r();
        return (u, v) -> (u - cx) * (u - cx) + (v - cy) * (v - cy) <= r * r;
    }

    private PointTest compile(Square square) {
        double cx = square.cx();
        double cy = square.cy();
        double side = square.side();
        double c = StrictMath.cos(square.rotation());
        double s = StrictMath.sin(square.rotation());
        return (u, v) -> {
            double dx = u - cx;
            double dy = v - cy;
            return Math.abs(dx * c + dy * s) <= side / 2 && Math.abs(-dx * s + dy * c) <= side / 2;
        };
    }

    private PointTest compile(Triangle t) {
        double x1 = t.x1();
        double y1 = t.y1();
        double x2 = t.x2();
        double y2 = t.y2();
        double x3 = t.x3();
        double y3 = t.y3();
        return (u, v) -> (x2 - x1) * (v - y1) - (y2 - y1) * (u - x1) >= 0
                && (x3 - x2) * (v - y2) - (y3 - y2) * (u - x2) >= 0
                && (x1 - x3) * (v - y3) - (y1 - y3) * (u - x3) >= 0;
    }

    /** The closed inside test of one compiled canonical shape. */
    private interface PointTest {

        boolean contains(double u, double v);
    }
}
