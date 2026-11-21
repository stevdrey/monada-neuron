package monada.neuron.visual;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the canonical form of a shape and the pose that maps it back (contract {@code visual-geometry/1} Sections
 * 4.2 and 4.8): center the bounding box, scale the larger extent to {@code 1}, remove the rotation, snap to the grid.
 *
 * <p>Rejections are checked in a fixed order: the composite caps before any work, then the pose translation range,
 * then collapse on the grid. Edge lengths and shoelace sums are exact, on {@code long} numerators; only the rotation
 * and rescaling run in {@code double}, with {@code StrictMath} trigonometry.
 */
final class ShapeCanonicalizer {

    static final int MAX_PARTS = 16;
    static final int MAX_HOLES = 16;
    private static final double CENTER = 0.5;

    /** A canonical primitive with the pose that maps it onto the input. */
    record CanonicalForm(ShapePrimitive primitive, Transform2D pose) {
    }

    Result<CanonicalForm> canonicalForm(ShapePrimitive primitive) {
        return switch (primitive) {
            case Circle c -> posed(new Circle(CENTER, CENTER, CENTER), c.cx() - CENTER, c.cy() - CENTER, 2 * c.r(), 0);
            case Square s -> posed(new Square(CENTER, CENTER, 1, 0), s.cx() - CENTER, s.cy() - CENTER, s.side(),
                    s.rotation());
            case Triangle triangle -> triangleForm(triangle);
            case Composite composite -> compositeForm(composite);
        };
    }

    private Result<CanonicalForm> posed(ShapePrimitive canonical, double tx, double ty, double scale, double rotation) {
        if (!inTranslationRange(tx) || !inTranslationRange(ty)) {
            return rejected(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE);
        }
        return new Result.Ok<>(new CanonicalForm(canonical, new Transform2D(tx, ty, scale, rotation)));
    }

    // Written so that NaN counts as out of range.
    private boolean inTranslationRange(double value) {
        return value >= Transform2D.MIN_TRANSLATION && value <= Transform2D.MAX_TRANSLATION;
    }

    private Result<CanonicalForm> rejected(VisualRejectionReason reason) {
        return new Result.Rejected<>(new VisualRejection(reason));
    }

    private Result<CanonicalForm> triangleForm(Triangle input) {
        BaseCandidate chosen = chooseBase(input);
        if (!inTranslationRange(chosen.tx()) || !inTranslationRange(chosen.ty())) {
            return rejected(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE);
        }
        if (chosen.isCollapsed()) {
            return rejected(VisualRejectionReason.BELOW_GRID_RESOLUTION);
        }
        Triangle snapped = chosen.triangle();
        // A canonical triangle must canonicalize to itself with the identity pose; otherwise its longest edge or its
        // centering is not stable at grid resolution and two poses of one triangle could compare unequal.
        if (!chooseBase(snapped).isIdentityOf(chosen)) {
            return rejected(VisualRejectionReason.BELOW_GRID_RESOLUTION);
        }
        return new Result.Ok<>(new CanonicalForm(snapped,
                new Transform2D(chosen.tx(), chosen.ty(), chosen.scale(), chosen.theta())));
    }

    /**
     * Picks the base edge: the longest input edge by exact squared length, then the lexicographically smallest
     * snapped vertex sequence, then the lowest stored edge. Ranking on snapped lengths would be wrong: for the
     * catalogue equilateral all three snapped candidates keep their bottom edge longest.
     */
    private BaseCandidate chooseBase(Triangle t) {
        double[] xs = {t.x1(), t.x2(), t.x3()};
        double[] ys = {t.y1(), t.y2(), t.y3()};
        long[] squaredLength = new long[3];
        long longest = 0;
        for (int k = 0; k < 3; k++) {
            int next = (k + 1) % 3;
            long dx = DyadicGrid.numerator(xs[next]) - DyadicGrid.numerator(xs[k]);
            long dy = DyadicGrid.numerator(ys[next]) - DyadicGrid.numerator(ys[k]);
            squaredLength[k] = dx * dx + dy * dy;
            longest = Math.max(longest, squaredLength[k]);
        }
        BaseCandidate chosen = null;
        for (int k = 0; k < 3; k++) {
            if (squaredLength[k] == longest) {
                BaseCandidate candidate = candidate(xs, ys, k);
                if (chosen == null || candidate.compareVertices(chosen) < 0) {
                    chosen = candidate;
                }
            }
        }
        return chosen;
    }

    /**
     * The canonical triangle with stored edge {@code k} as its base. For a clockwise triangle the base runs from
     * vertex {@code k + 1} (bottom left) to vertex {@code k} (bottom right), which puts the third vertex above.
     */
    private BaseCandidate candidate(double[] xs, double[] ys, int k) {
        int right = k;
        int left = (k + 1) % 3;
        int apex = (k + 2) % 3;
        double theta = StrictMath.atan2(ys[right] - ys[left], xs[right] - xs[left]);
        if (theta == -Math.PI) {
            theta = Math.PI;
        }
        double cos = StrictMath.cos(theta);
        double sin = StrictMath.sin(theta);
        double[] rx = new double[3];
        double[] ry = new double[3];
        for (int i = 0; i < 3; i++) {
            rx[i] = xs[i] * cos + ys[i] * sin;
            ry[i] = -xs[i] * sin + ys[i] * cos;
        }
        double minX = Math.min(rx[0], Math.min(rx[1], rx[2]));
        double maxX = Math.max(rx[0], Math.max(rx[1], rx[2]));
        double minY = Math.min(ry[0], Math.min(ry[1], ry[2]));
        double maxY = Math.max(ry[0], Math.max(ry[1], ry[2]));
        double mx = (minX + maxX) / 2;
        double my = (minY + maxY) / 2;
        double scale = Math.max(maxX - minX, maxY - minY);

        double apexY = ((ry[apex] - my) / scale + CENTER) * DyadicGrid.STEPS_PER_UNIT;
        double baseY = (((ry[right] - my) / scale + CENTER) + ((ry[left] - my) / scale + CENTER)) / 2
                * DyadicGrid.STEPS_PER_UNIT;
        long ax = Math.round(Math.rint(((rx[apex] - mx) / scale + CENTER) * DyadicGrid.STEPS_PER_UNIT));
        long bx = Math.round(Math.rint(((rx[right] - mx) / scale + CENTER) * DyadicGrid.STEPS_PER_UNIT));
        long lx = Math.round(Math.rint(((rx[left] - mx) / scale + CENTER) * DyadicGrid.STEPS_PER_UNIT));
        long ay = Math.round(Math.rint(apexY));
        long by = Math.round(Math.rint(baseY));
        long base = (bx - lx) * (bx - lx);
        long rise = (by - ay) * (by - ay);
        if ((ax - bx) * (ax - bx) + rise > base || (ax - lx) * (ax - lx) + rise > base) {
            // Section 4.8 item 1: round both inward so the bottom edge stays the longest and the box stays centered.
            ay = Math.round(Math.ceil(apexY));
            by = Math.round(Math.floor(baseY));
        }
        return new BaseCandidate(ax, ay, bx, lx, by,
                mx * cos - my * sin - CENTER, mx * sin + my * cos - CENTER, scale, theta);
    }

    private Result<CanonicalForm> compositeForm(Composite input) {
        if (input.parts().size() > MAX_PARTS || input.holes().size() > MAX_HOLES) {
            return rejected(VisualRejectionReason.CAP_EXCEEDED);
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (ShapePrimitive part : input.parts()) {
            switch (part) {
                case Circle c -> {
                    minX = Math.min(minX, c.cx() - c.r());
                    maxX = Math.max(maxX, c.cx() + c.r());
                    minY = Math.min(minY, c.cy() - c.r());
                    maxY = Math.max(maxY, c.cy() + c.r());
                }
                case Square s -> {
                    double half = s.side() / 2 * (Math.abs(StrictMath.cos(s.rotation()))
                            + Math.abs(StrictMath.sin(s.rotation())));
                    minX = Math.min(minX, s.cx() - half);
                    maxX = Math.max(maxX, s.cx() + half);
                    minY = Math.min(minY, s.cy() - half);
                    maxY = Math.max(maxY, s.cy() + half);
                }
                case Triangle t -> {
                    minX = Math.min(minX, Math.min(t.x1(), Math.min(t.x2(), t.x3())));
                    maxX = Math.max(maxX, Math.max(t.x1(), Math.max(t.x2(), t.x3())));
                    minY = Math.min(minY, Math.min(t.y1(), Math.min(t.y2(), t.y3())));
                    maxY = Math.max(maxY, Math.max(t.y1(), Math.max(t.y2(), t.y3())));
                }
                case Composite _ -> throw new IllegalStateException("a composite never holds a composite");
            }
        }
        double mx = (minX + maxX) / 2;
        double my = (minY + maxY) / 2;
        double scale = Math.max(maxX - minX, maxY - minY);
        if (!inTranslationRange(mx - CENTER) || !inTranslationRange(my - CENTER)) {
            return rejected(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE);
        }
        List<ShapePrimitive> parts = rescaled(input.parts(), mx, my, scale);
        List<ShapePrimitive> holes = rescaled(input.holes(), mx, my, scale);
        if (parts == null || holes == null) {
            return rejected(VisualRejectionReason.BELOW_GRID_RESOLUTION);
        }
        return new Result.Ok<>(new CanonicalForm(new Composite(parts, holes),
                new Transform2D(mx - CENTER, my - CENTER, scale, 0)));
    }

    /** The entries in the canonical frame, snapped, with snap duplicates kept once; null when one does not fit the grid. */
    private List<ShapePrimitive> rescaled(List<ShapePrimitive> entries, double mx, double my, double scale) {
        List<ShapePrimitive> result = new ArrayList<>(entries.size());
        for (ShapePrimitive entry : entries) {
            ShapePrimitive snapped = switch (entry) {
                case Circle c -> {
                    double[] v = snapAll(c.cx(), c.cy(), mx, my, scale);
                    double r = DyadicGrid.snap(c.r() / scale);
                    yield v == null || r == 0 || !DyadicGrid.isOnGrid(r) ? null : new Circle(v[0], v[1], r);
                }
                case Square s -> {
                    double[] v = snapAll(s.cx(), s.cy(), mx, my, scale);
                    double side = DyadicGrid.snap(s.side() / scale);
                    yield v == null || side == 0 || !DyadicGrid.isOnGrid(side)
                            ? null : new Square(v[0], v[1], side, s.rotation());
                }
                case Triangle t -> {
                    double[] a = snapAll(t.x1(), t.y1(), mx, my, scale);
                    double[] b = snapAll(t.x2(), t.y2(), mx, my, scale);
                    double[] c = snapAll(t.x3(), t.y3(), mx, my, scale);
                    yield a == null || b == null || c == null || isCollinear(a, b, c)
                            ? null : new Triangle(a[0], a[1], b[0], b[1], c[0], c[1]);
                }
                case Composite _ -> throw new IllegalStateException("a composite never holds a composite");
            };
            if (snapped == null) {
                return null;
            }
            result.add(snapped);
        }
        result.sort(Composite.CANONICAL_ORDER);
        for (int i = result.size() - 1; i > 0; i--) {
            if (result.get(i).equals(result.get(i - 1))) {
                result.remove(i);
            }
        }
        return result;
    }

    /** One point in the canonical frame, snapped; null when it lands beyond the numerator bound. */
    private double[] snapAll(double x, double y, double mx, double my, double scale) {
        double sx = DyadicGrid.snap((x - mx) / scale + CENTER);
        double sy = DyadicGrid.snap((y - my) / scale + CENTER);
        return DyadicGrid.isOnGrid(sx) && DyadicGrid.isOnGrid(sy) ? new double[] {sx, sy} : null;
    }

    private boolean isCollinear(double[] a, double[] b, double[] c) {
        long ax = DyadicGrid.numerator(a[0]);
        long ay = DyadicGrid.numerator(a[1]);
        return (DyadicGrid.numerator(b[0]) - ax) * (DyadicGrid.numerator(c[1]) - ay)
                == (DyadicGrid.numerator(c[0]) - ax) * (DyadicGrid.numerator(b[1]) - ay);
    }

    /**
     * One base choice: snapped numerators of the apex {@code (ax, ay)}, bottom right {@code (bx, by)} and bottom
     * left {@code (lx, by)}, in the stored order of a canonical triangle, with the pose that maps it onto the input.
     */
    private record BaseCandidate(long ax, long ay, long bx, long lx, long by,
                                 double tx, double ty, double scale, double theta) {

        boolean isCollapsed() {
            return bx == lx || by == ay;
        }

        Triangle triangle() {
            return new Triangle(DyadicGrid.ofNumerator(ax), DyadicGrid.ofNumerator(ay),
                    DyadicGrid.ofNumerator(bx), DyadicGrid.ofNumerator(by),
                    DyadicGrid.ofNumerator(lx), DyadicGrid.ofNumerator(by));
        }

        int compareVertices(BaseCandidate other) {
            long[] mine = {ax, ay, bx, by, lx, by};
            long[] theirs = {other.ax, other.ay, other.bx, other.by, other.lx, other.by};
            for (int i = 0; i < mine.length; i++) {
                if (mine[i] != theirs[i]) {
                    return Long.compare(mine[i], theirs[i]);
                }
            }
            return 0;
        }

        boolean isIdentityOf(BaseCandidate first) {
            return compareVertices(first) == 0 && tx == 0 && ty == 0 && scale == 1 && theta == 0;
        }
    }
}
