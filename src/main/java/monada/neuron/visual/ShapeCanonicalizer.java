package monada.neuron.visual;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the canonical form of a shape and the pose that maps it back (contract {@code visual-geometry/1} Sections
 * 4.2 and 4.8): center the bounding box, scale the larger extent to {@code 1}, remove the rotation, snap to the grid.
 *
 * <p>Rejections are checked in a fixed order: the composite caps before any work, then the pose translation range,
 * then collapse on the grid. Edge lengths, shoelace sums and the snapped numerators of a triangle are exact, on
 * {@code long} numerators, so a triangle snaps the same at every grid exact pose; only the pose and the rescaling of a
 * composite run in {@code double}, with {@code StrictMath} trigonometry.
 */
final class ShapeCanonicalizer {

    static final int MAX_PARTS = 16;
    static final int MAX_HOLES = 16;
    private static final double CENTER = Transform2D.CENTER;
    private static final int GRID_BITS = DyadicGrid.BITS;
    private static final long GRID_STEPS = DyadicGrid.STEPS;

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
        double[] xs = {input.x1(), input.x2(), input.x3()};
        double[] ys = {input.y1(), input.y2(), input.y3()};
        SnappedBase chosen = chooseBase(numerators(xs), numerators(ys));
        BasePose pose = pose(xs, ys, chosen.edge());
        if (!inTranslationRange(pose.tx()) || !inTranslationRange(pose.ty())) {
            return rejected(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE);
        }
        if (chosen.isCollapsed()) {
            return rejected(VisualRejectionReason.BELOW_GRID_RESOLUTION);
        }
        Triangle snapped = chosen.triangle();
        // A canonical triangle must canonicalize to itself with the identity pose; otherwise its longest edge or its
        // centering is not stable at grid resolution and two poses of one triangle could compare unequal.
        if (!isFixedPoint(snapped, chosen)) {
            return rejected(VisualRejectionReason.BELOW_GRID_RESOLUTION);
        }
        return new Result.Ok<>(new CanonicalForm(snapped,
                new Transform2D(pose.tx(), pose.ty(), pose.scale(), pose.theta())));
    }

    private long[] numerators(double[] values) {
        return new long[] {DyadicGrid.numerator(values[0]), DyadicGrid.numerator(values[1]),
                DyadicGrid.numerator(values[2])};
    }

    /**
     * Whether a snapped triangle canonicalizes to itself with the identity pose, on numerators only. The identity pose
     * means the chosen base is horizontal and runs left to right (rotation {@code 0}), the box is centered
     * (translation {@code 0}) and its larger extent is one unit (scale {@code 1}); with rotation {@code 0} the pose
     * arithmetic of {@link #pose} is exact, so these integer tests decide the same thing without trigonometry.
     */
    private boolean isFixedPoint(Triangle snapped, SnappedBase chosen) {
        long[] nx = numerators(new double[] {snapped.x1(), snapped.x2(), snapped.x3()});
        long[] ny = numerators(new double[] {snapped.y1(), snapped.y2(), snapped.y3()});
        SnappedBase again = chooseBase(nx, ny);
        if (again.compareVertices(chosen) != 0) {
            return false;
        }
        int right = again.edge();
        int left = (right + 1) % 3;
        if (ny[right] != ny[left] || nx[right] <= nx[left]) {
            return false;
        }
        long minX = Math.min(nx[0], Math.min(nx[1], nx[2]));
        long maxX = Math.max(nx[0], Math.max(nx[1], nx[2]));
        long minY = Math.min(ny[0], Math.min(ny[1], ny[2]));
        long maxY = Math.max(ny[0], Math.max(ny[1], ny[2]));
        return minX + maxX == GRID_STEPS && minY + maxY == GRID_STEPS
                && Math.max(maxX - minX, maxY - minY) == GRID_STEPS;
    }

    /**
     * Picks the base edge: the longest input edge by exact squared length, then the lexicographically smallest
     * snapped vertex sequence, then the lowest stored edge. Ranking on snapped lengths would be wrong: for the
     * catalogue equilateral all three snapped candidates keep their bottom edge longest. Integer only.
     */
    private SnappedBase chooseBase(long[] nx, long[] ny) {
        long[] squaredLength = new long[3];
        long longest = 0;
        for (int k = 0; k < 3; k++) {
            int next = (k + 1) % 3;
            long dx = nx[next] - nx[k];
            long dy = ny[next] - ny[k];
            squaredLength[k] = dx * dx + dy * dy;
            longest = Math.max(longest, squaredLength[k]);
        }
        SnappedBase chosen = null;
        for (int k = 0; k < 3; k++) {
            if (squaredLength[k] == longest) {
                SnappedBase candidate = snappedBase(nx, ny, k);
                if (chosen == null || candidate.compareVertices(chosen) < 0) {
                    chosen = candidate;
                }
            }
        }
        return chosen;
    }

    /**
     * The pose that maps the canonical triangle with stored edge {@code k} as its base onto the input. For a
     * clockwise triangle the base runs from vertex {@code k + 1} (bottom left) to vertex {@code k} (bottom right),
     * which puts the third vertex above.
     */
    private BasePose pose(double[] xs, double[] ys, int k) {
        int right = k;
        int left = (k + 1) % 3;
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
        return new BasePose(mx * cos - my * sin - CENTER, mx * sin + my * cos - CENTER, scale, theta);
    }

    /**
     * The snapped canonical triangle with stored edge {@code k} as its base.
     *
     * <p>The snapped numerators come from exact integer ratios of the input numerators, never from rotated doubles:
     * rounding noise there differs per pose and flips a half grid tie, so two grid exact poses of one triangle could
     * snap one step apart. The base is the longest edge, so the box is as wide as the base, the apex sits at
     * dot / base² along it and cross / base² above it, and both survive any exact rigid motion.
     */
    private SnappedBase snappedBase(long[] nx, long[] ny, int k) {
        int right = k;
        int left = (k + 1) % 3;
        int apex = (k + 2) % 3;
        long baseX = nx[right] - nx[left];
        long baseY = ny[right] - ny[left];
        long apexX = nx[apex] - nx[left];
        long apexY = ny[apex] - ny[left];
        long squaredBase = baseX * baseX + baseY * baseY;
        long cross = apexX * baseY - apexY * baseX;
        long lx = 0;
        long bx = GRID_STEPS;
        long ax = scaledRatio(apexX * baseX + apexY * baseY, squaredBase, GRID_BITS, true);
        long halfHeight = scaledRatio(cross, squaredBase, GRID_BITS - 1, true);
        long ay = GRID_STEPS / 2 - halfHeight;
        long by = GRID_STEPS / 2 + halfHeight;
        long base = (bx - lx) * (bx - lx);
        long rise = (by - ay) * (by - ay);
        if ((ax - bx) * (ax - bx) + rise > base || (ax - lx) * (ax - lx) + rise > base) {
            // Section 4.8 item 1: round both inward so the bottom edge stays the longest and the box stays centered.
            long inward = scaledRatio(cross, squaredBase, GRID_BITS - 1, false);
            ay = GRID_STEPS / 2 - inward;
            by = GRID_STEPS / 2 + inward;
        }
        return new SnappedBase(k, ax, ay, bx, lx, by);
    }

    /**
     * {@code numerator * 2^bits / denominator} as an exact integer: the floor, or the nearest with ties to even.
     * Long division one bit at a time, because the product itself does not fit a {@code long}; the remainder stays
     * below the denominator, which is at most {@code 2^59}, so doubling it never overflows.
     */
    private long scaledRatio(long numerator, long denominator, int bits, boolean nearest) {
        long quotient = Math.floorDiv(numerator, denominator);
        long remainder = Math.floorMod(numerator, denominator);
        for (int bit = 0; bit < bits; bit++) {
            quotient <<= 1;
            remainder <<= 1;
            if (remainder >= denominator) {
                remainder -= denominator;
                quotient |= 1;
            }
        }
        if (nearest) {
            long twice = remainder << 1;
            if (twice > denominator || (twice == denominator && (quotient & 1) == 1)) {
                quotient++;
            }
        }
        return quotient;
    }

    private Result<CanonicalForm> compositeForm(Composite input) {
        if (input.parts().size() > MAX_PARTS || input.holes().size() > MAX_HOLES) {
            return rejected(VisualRejectionReason.CAP_EXCEEDED);
        }
        BoundingBox box = BoundingBox.of(input.parts().getFirst());
        for (ShapePrimitive part : input.parts()) {
            box = box.union(BoundingBox.of(part));
        }
        double mx = box.centerX();
        double my = box.centerY();
        double scale = box.extent();
        if (!inTranslationRange(mx - CENTER) || !inTranslationRange(my - CENTER)) {
            return rejected(VisualRejectionReason.TRANSFORM_OUT_OF_RANGE);
        }
        List<ShapePrimitive> parts = rescaled(input.parts(), mx, my, scale);
        List<ShapePrimitive> holes = rescaled(input.holes(), mx, my, scale);
        if (parts == null || holes == null) {
            return rejected(VisualRejectionReason.BELOW_GRID_RESOLUTION);
        }
        return new Result.Ok<>(new CanonicalForm(Composite.keepingEachShapeOnce(parts, holes),
                new Transform2D(mx - CENTER, my - CENTER, scale, 0)));
    }

    /** The entries in the canonical frame, snapped; null when one does not fit the grid. */
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
                    yield a == null || b == null || c == null
                            || DyadicGrid.shoelace(a[0], a[1], b[0], b[1], c[0], c[1]) == 0
                            ? null : new Triangle(a[0], a[1], b[0], b[1], c[0], c[1]);
                }
                case Composite _ -> throw new IllegalStateException("a composite never holds a composite");
            };
            if (snapped == null) {
                return null;
            }
            result.add(snapped);
        }
        return result;
    }

    /** One point in the canonical frame, snapped; null when it lands beyond the numerator bound. */
    private double[] snapAll(double x, double y, double mx, double my, double scale) {
        double sx = DyadicGrid.snap((x - mx) / scale + CENTER);
        double sy = DyadicGrid.snap((y - my) / scale + CENTER);
        return DyadicGrid.isOnGrid(sx) && DyadicGrid.isOnGrid(sy) ? new double[] {sx, sy} : null;
    }

    /** The pose of one base choice: the similarity that maps the canonical triangle onto the input. */
    private record BasePose(double tx, double ty, double scale, double theta) {
    }

    /**
     * One base choice, integer only: the stored edge {@code edge} taken as base, and the snapped numerators of the
     * apex {@code (ax, ay)}, bottom right {@code (bx, by)} and bottom left {@code (lx, by)}, in the stored order of a
     * canonical triangle.
     */
    private record SnappedBase(int edge, long ax, long ay, long bx, long lx, long by) {

        boolean isCollapsed() {
            return bx == lx || by == ay;
        }

        Triangle triangle() {
            return new Triangle(DyadicGrid.ofNumerator(ax), DyadicGrid.ofNumerator(ay),
                    DyadicGrid.ofNumerator(bx), DyadicGrid.ofNumerator(by),
                    DyadicGrid.ofNumerator(lx), DyadicGrid.ofNumerator(by));
        }

        int compareVertices(SnappedBase other) {
            long[] mine = {ax, ay, bx, by, lx, by};
            long[] theirs = {other.ax, other.ay, other.bx, other.by, other.lx, other.by};
            for (int i = 0; i < mine.length; i++) {
                if (mine[i] != theirs[i]) {
                    return Long.compare(mine[i], theirs[i]);
                }
            }
            return 0;
        }
    }
}
