package monada.neuron.visual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A union of parts minus a union of holes: a point is inside when it is inside any part and inside no hole, both by
 * the closed rule (contract {@code visual-geometry/1} Section 4.3). It describes multi part silhouettes, holes and
 * disconnected components without naming what they depict.
 *
 * <p>Both lists are copied, stored unmodifiable and sorted in the contract canonical order (Section 4.2): by center
 * {@code y}, then center {@code x}, then kind ordinal, then the remaining parameters in declaration order. A
 * triangle's center is its bounding box center. Any argument order therefore gives an equal record.
 *
 * <p>The constructor throws {@link IllegalArgumentException} for a null list or element, no parts, a nested
 * {@code Composite}, or two identical entries within one list. A part equal to a hole is allowed, and so is a hole
 * that misses every part. The caps of 16 parts and 16 holes are not checked here: a larger value is constructible and
 * every operation that receives it returns {@link VisualRejectionReason#CAP_EXCEEDED}.
 *
 * @param parts the filled shapes, at least one, none a composite
 * @param holes the shapes cut out, possibly empty, none a composite
 */
public record Composite(List<ShapePrimitive> parts, List<ShapePrimitive> holes) implements ShapePrimitive {

    static final Comparator<ShapePrimitive> CANONICAL_ORDER = new CanonicalOrder();

    public Composite {
        parts = canonicalList(parts, "parts");
        holes = canonicalList(holes, "holes");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("a composite needs at least one part");
        }
    }

    /**
     * A composite over entries that may hold the same shape more than once, each kept once. Canonicalization uses it
     * after snapping, where two distinct entries can land on one grid shape; the public constructor still throws on a
     * repeated entry.
     */
    static Composite keepingEachShapeOnce(List<ShapePrimitive> parts, List<ShapePrimitive> holes) {
        return new Composite(distinct(parts), distinct(holes));
    }

    private static List<ShapePrimitive> distinct(List<ShapePrimitive> entries) {
        List<ShapePrimitive> sorted = new ArrayList<>(entries);
        sorted.sort(CANONICAL_ORDER);
        for (int i = sorted.size() - 1; i > 0; i--) {
            if (sorted.get(i).equals(sorted.get(i - 1))) {
                sorted.remove(i);
            }
        }
        return sorted;
    }

    private static List<ShapePrimitive> canonicalList(List<ShapePrimitive> entries, String name) {
        if (entries == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        for (ShapePrimitive entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
            if (entry instanceof Composite) {
                throw new IllegalArgumentException(name + " must not contain a composite");
            }
        }
        List<ShapePrimitive> sorted = new ArrayList<>(entries);
        sorted.sort(CANONICAL_ORDER);
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1))) {
                throw new IllegalArgumentException(name + " must not contain the same shape twice: " + sorted.get(i));
            }
        }
        return List.copyOf(sorted);
    }

    /** Section 4.2 order over non composite primitives; equal shapes, and only those, compare as zero. */
    private static final class CanonicalOrder implements Comparator<ShapePrimitive> {

        @Override
        public int compare(ShapePrimitive a, ShapePrimitive b) {
            int order = Double.compare(centerY(a), centerY(b));
            if (order == 0) {
                order = Double.compare(centerX(a), centerX(b));
            }
            if (order == 0) {
                order = Integer.compare(kindOrdinal(a), kindOrdinal(b));
            }
            return order != 0 ? order : compareParameters(a, b);
        }

        private double centerX(ShapePrimitive shape) {
            return switch (shape) {
                case Circle circle -> circle.cx();
                case Square square -> square.cx();
                case Triangle t -> BoundingBox.of(t).centerX();
                case Composite _ -> throw new IllegalArgumentException("a composite has no canonical order key");
            };
        }

        private double centerY(ShapePrimitive shape) {
            return switch (shape) {
                case Circle circle -> circle.cy();
                case Square square -> square.cy();
                case Triangle t -> BoundingBox.of(t).centerY();
                case Composite _ -> throw new IllegalArgumentException("a composite has no canonical order key");
            };
        }

        private int kindOrdinal(ShapePrimitive shape) {
            return switch (shape) {
                case Circle _ -> 0;
                case Square _ -> 1;
                case Triangle _ -> 2;
                case Composite _ -> 3;
            };
        }

        private int compareParameters(ShapePrimitive a, ShapePrimitive b) {
            return switch (a) {
                case Circle circle -> Double.compare(circle.r(), ((Circle) b).r());
                case Square square -> {
                    Square other = (Square) b;
                    int order = Double.compare(square.side(), other.side());
                    yield order != 0 ? order : Double.compare(square.rotation(), other.rotation());
                }
                case Triangle t -> {
                    Triangle other = (Triangle) b;
                    double[] mine = {t.x1(), t.y1(), t.x2(), t.y2(), t.x3(), t.y3()};
                    double[] theirs = {other.x1(), other.y1(), other.x2(), other.y2(), other.x3(), other.y3()};
                    int order = 0;
                    for (int i = 0; i < mine.length && order == 0; i++) {
                        order = Double.compare(mine[i], theirs[i]);
                    }
                    yield order;
                }
                case Composite _ -> throw new IllegalArgumentException("a composite has no canonical order key");
            };
        }
    }
}
