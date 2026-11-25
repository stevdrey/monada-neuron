package monada.neuron.evaluation.visual;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Renders mask fixtures: gold shapes by the render arithmetic of contract Section 4.4, and the negative and ambiguous
 * ids by the pixel predicates of Section 8.5. Every expression is evaluated in {@code double}, left to right as the
 * contract writes it, with {@link StrictMath} for trigonometry and no fused multiply add, so the pixels are the same
 * on every platform. This is an independent second implementation of Section 4.4 on purpose (spec 0013).
 */
final class MaskRenderer {

    /** A foreground mask byte; background is {@code 0}. */
    static final byte LIT = (byte) 255;

    private static final double GRID = 4096.0;
    private static final GoldTransform IDENTITY = new GoldTransform(0.0, 0.0, 1.0, 0.0);
    private static final GoldShape SLIVER_32 = new GoldShape.Triangle(2048, 1984, 3584, 2112, 512, 2112);
    private static final GoldShape SLIVER_64 = new GoldShape.Triangle(2048, 2016, 3584, 2080, 512, 2080);

    /** Whether pixel {@code (x, y)} is foreground. */
    @FunctionalInterface
    interface PixelTest {

        boolean lit(int x, int y);
    }

    /**
     * The map of Section 4.4 from a pixel of an {@code n x n} raster to its canonical point, for one pose.
     *
     * @param n the raster size
     * @param transform the pose
     * @param cos {@code StrictMath.cos} of the pose rotation, taken once per render
     * @param sin {@code StrictMath.sin} of the pose rotation, taken once per render
     */
    record Projection(int n, GoldTransform transform, double cos, double sin) {

        double u(int x, int y) {
            double qx = (x + 0.5) / n - 0.5 - transform.tx();
            double qy = (y + 0.5) / n - 0.5 - transform.ty();
            return (qx * cos + qy * sin) / transform.scale() + 0.5;
        }

        double v(int x, int y) {
            double qx = (x + 0.5) / n - 0.5 - transform.tx();
            double qy = (y + 0.5) / n - 0.5 - transform.ty();
            return (-qx * sin + qy * cos) / transform.scale() + 0.5;
        }
    }

    /** The decoded mask bytes of a mask fixture at size {@code n}, in raster order. */
    byte[] render(FixtureDefinition definition, int n, long seed) {
        return switch (definition.group()) {
            case POSITIVE, COMPOSITE ->
                    renderShape(definition.shape().orElseThrow(), definition.transform().orElseThrow(), n);
            case NEGATIVE, AMBIGUOUS -> fill(n, predicate(definition.id(), n, seed));
            default -> throw new IllegalArgumentException(definition.id() + " is not a mask fixture");
        };
    }

    /** The analytic render of a gold shape at a pose. */
    byte[] renderShape(GoldShape shape, GoldTransform transform, int n) {
        return fill(n, shapeTest(shape, transform, n));
    }

    /**
     * Whether a pose clips at the raster edge: the same render taken over pixel indices {@code -n} to {@code 2n - 1}
     * lights a pixel outside {@code 0} to {@code n - 1}.
     */
    boolean clipped(GoldShape shape, GoldTransform transform, int n) {
        PixelTest test = shapeTest(shape, transform, n);
        for (int y = -n; y < 2 * n; y++) {
            for (int x = -n; x < 2 * n; x++) {
                boolean outside = x < 0 || x >= n || y < 0 || y >= n;
                if (outside && test.lit(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    Projection projection(GoldTransform transform, int n) {
        return new Projection(n, transform, StrictMath.cos(transform.rotation()),
                StrictMath.sin(transform.rotation()));
    }

    /** The closed center rule on a canonical point; a point outside the unit square is background. */
    boolean covers(GoldShape shape, double u, double v) {
        if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) {
            return false;
        }
        return inside(shape, u, v);
    }

    private PixelTest shapeTest(GoldShape shape, GoldTransform transform, int n) {
        Projection projection = projection(transform, n);
        return (x, y) -> covers(shape, projection.u(x, y), projection.v(x, y));
    }

    private boolean inside(GoldShape shape, double u, double v) {
        return switch (shape) {
            case GoldShape.Circle circle -> {
                double cx = circle.cx() / GRID;
                double cy = circle.cy() / GRID;
                double r = circle.r() / GRID;
                yield (u - cx) * (u - cx) + (v - cy) * (v - cy) <= r * r;
            }
            case GoldShape.Square square -> {
                double c = StrictMath.cos(square.rotation() / GRID);
                double s = StrictMath.sin(square.rotation() / GRID);
                double side = square.side() / GRID;
                double dx = u - square.cx() / GRID;
                double dy = v - square.cy() / GRID;
                yield Math.abs(dx * c + dy * s) <= side / 2 && Math.abs(-dx * s + dy * c) <= side / 2;
            }
            case GoldShape.Triangle triangle -> {
                double x1 = triangle.x1() / GRID;
                double y1 = triangle.y1() / GRID;
                double x2 = triangle.x2() / GRID;
                double y2 = triangle.y2() / GRID;
                double x3 = triangle.x3() / GRID;
                double y3 = triangle.y3() / GRID;
                yield edge(x1, y1, x2, y2, u, v) && edge(x2, y2, x3, y3, u, v) && edge(x3, y3, x1, y1, u, v);
            }
            case GoldShape.Composite composite -> anyInside(composite.parts(), u, v)
                    && !anyInside(composite.holes(), u, v);
        };
    }

    private boolean anyInside(List<GoldShape> shapes, double u, double v) {
        for (GoldShape shape : shapes) {
            if (inside(shape, u, v)) {
                return true;
            }
        }
        return false;
    }

    /** The edge function of Section 4.4 for the edge {@code (x1, y1) -> (x2, y2)}. */
    private boolean edge(double x1, double y1, double x2, double y2, double u, double v) {
        return (x2 - x1) * (v - y1) - (y2 - y1) * (u - x1) >= 0;
    }

    /** Asks the test once per pixel in raster order; the seeded draws of Section 8.2 depend on that order. */
    private byte[] fill(int n, PixelTest test) {
        var mask = new byte[n * n];
        for (var y = 0; y < n; y++) {
            for (var x = 0; x < n; x++) {
                if (test.lit(x, y)) {
                    mask[y * n + x] = LIT;
                }
            }
        }
        return mask;
    }

    private PixelTest predicate(String id, int n, long seed) {
        return switch (id) {
            case "m-neg-01" -> (x, y) -> false;
            case "m-neg-02" -> (x, y) -> true;
            case "m-neg-03" -> (x, y) -> !(x == n / 2 && y == n / 2);
            case "m-neg-04" -> (x, y) -> x == 0 || x == n - 1 || y == 0 || y == n - 1;
            case "m-neg-05" -> (x, y) -> x == n / 2 && y == n / 2;
            case "m-neg-06" -> (x, y) -> x == 0 && y == 0;
            case "m-neg-07" -> (x, y) -> y == n / 2;
            case "m-neg-08" -> (x, y) -> x == y;
            case "m-neg-09" -> (x, y) -> (x + y) % 2 == 0;
            case "m-neg-10", "m-neg-11" -> bernoulli(seed, 0.5);
            case "m-neg-12" -> bernoulli(seed, 0.05);
            case "m-neg-13" -> (x, y) -> x == n / 2;
            case "m-neg-14" -> shapeTest(n == 32 ? SLIVER_32 : SLIVER_64, IDENTITY, n);
            case "m-amb-01" -> roundedSquare(n, 0.41);
            case "m-amb-02" -> roundedSquare(n, 0.45);
            case "m-amb-03" -> ellipse(n, 0.375, 0.3125);
            case "m-amb-04" -> ellipse(n, 0.225, 0.1875);
            case "m-amb-05" -> pentagon(n);
            case "m-amb-06" -> polygon(n, 0.5, 0.5, 0.375, 6, 0.0);
            case "m-amb-07" -> (x, y) -> {
                double d2 = squaredDistance(center(x, n), center(y, n), 0.5, 0.5);
                return d2 <= 0.375 * 0.375 && !(d2 <= (1.0 / n) * (1.0 / n));
            };
            case "m-amb-08" -> (x, y) -> squaredDistance(center(x, n), center(y, n), 0.5, 0.5) <= 0.5 * 0.5;
            case "m-amb-09" -> twoCircles(n, 0.35, 0.65);
            case "m-amb-10" -> twoCircles(n, 0.25, 0.75);
            default -> throw new IllegalArgumentException("no pixel predicate for " + id);
        };
    }

    /** One {@code nextLong()} per pixel; foreground when {@code (draw >>> 11) * 2^-53 < p} (Section 8.2). */
    private PixelTest bernoulli(long seed, double p) {
        var random = new SplittableRandom(seed);
        return (x, y) -> (random.nextLong() >>> 11) * 0x1.0p-53 < p;
    }

    private PixelTest roundedSquare(int n, double r) {
        return (x, y) -> {
            double u = center(x, n);
            double v = center(y, n);
            return squaredDistance(u, v, 0.5, 0.5) <= r * r && Math.abs(u - 0.5) <= 0.375
                    && Math.abs(v - 0.5) <= 0.375;
        };
    }

    private PixelTest ellipse(int n, double a, double b) {
        return (x, y) -> {
            double eu = (center(x, n) - 0.5) / a;
            double ev = (center(y, n) - 0.5) / b;
            return eu * eu + ev * ev <= 1;
        };
    }

    /** One vertex straight up, larger extent {@code 0.75}, bounding box centered (Section 8.5). */
    private PixelTest pentagon(int n) {
        double radius = 0.375 / StrictMath.sin(2 * Math.PI / 5);
        double cy = 0.5 + radius * (1 - StrictMath.cos(Math.PI / 5)) / 2;
        return polygon(n, 0.5, cy, radius, 5, -Math.PI / 2);
    }

    /** A regular polygon with vertices at {@code start + 2 pi k / count}, which is clockwise on screen. */
    private PixelTest polygon(int n, double cx, double cy, double radius, int count, double start) {
        var xs = new double[count];
        var ys = new double[count];
        for (var k = 0; k < count; k++) {
            double angle = start + 2 * Math.PI * k / count;
            xs[k] = cx + radius * StrictMath.cos(angle);
            ys[k] = cy + radius * StrictMath.sin(angle);
        }
        return (x, y) -> {
            double u = center(x, n);
            double v = center(y, n);
            for (var k = 0; k < count; k++) {
                int next = (k + 1) % count;
                if (!edge(xs[k], ys[k], xs[next], ys[next], u, v)) {
                    return false;
                }
            }
            return true;
        };
    }

    private PixelTest twoCircles(int n, double leftCx, double rightCx) {
        return (x, y) -> {
            double u = center(x, n);
            double v = center(y, n);
            return squaredDistance(u, v, leftCx, 0.5) <= 0.3 * 0.3 || squaredDistance(u, v, rightCx, 0.5) <= 0.3 * 0.3;
        };
    }

    private double center(int index, int n) {
        return (index + 0.5) / n;
    }

    private double squaredDistance(double u, double v, double cx, double cy) {
        return (u - cx) * (u - cx) + (v - cy) * (v - cy);
    }
}
