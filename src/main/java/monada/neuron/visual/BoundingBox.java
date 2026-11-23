package monada.neuron.visual;

/**
 * The axis aligned bounding box of a non composite primitive in the frame it is written in. One definition serves the
 * composite box (the union of its parts' boxes, contract {@code visual-geometry/1} Section 4.2) and the order key of a
 * triangle (its box center), so the two cannot drift apart.
 *
 * @param minX left edge
 * @param maxX right edge
 * @param minY top edge
 * @param maxY bottom edge
 */
record BoundingBox(double minX, double maxX, double minY, double maxY) {

    static BoundingBox of(ShapePrimitive shape) {
        return switch (shape) {
            case Circle c -> new BoundingBox(c.cx() - c.r(), c.cx() + c.r(), c.cy() - c.r(), c.cy() + c.r());
            case Square s -> {
                double half = s.side() / 2 * (Math.abs(StrictMath.cos(s.rotation()))
                        + Math.abs(StrictMath.sin(s.rotation())));
                yield new BoundingBox(s.cx() - half, s.cx() + half, s.cy() - half, s.cy() + half);
            }
            case Triangle t -> new BoundingBox(
                    Math.min(t.x1(), Math.min(t.x2(), t.x3())), Math.max(t.x1(), Math.max(t.x2(), t.x3())),
                    Math.min(t.y1(), Math.min(t.y2(), t.y3())), Math.max(t.y1(), Math.max(t.y2(), t.y3())));
            case Composite _ -> throw new IllegalArgumentException("a composite has no box of its own");
        };
    }

    BoundingBox union(BoundingBox other) {
        return new BoundingBox(Math.min(minX, other.minX), Math.max(maxX, other.maxX),
                Math.min(minY, other.minY), Math.max(maxY, other.maxY));
    }

    double centerX() {
        return (minX + maxX) / 2;
    }

    double centerY() {
        return (minY + maxY) / 2;
    }

    /** The larger of the width and the height. */
    double extent() {
        return Math.max(maxX - minX, maxY - minY);
    }
}
