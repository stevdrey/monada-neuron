package monada.neuron.visual;

/**
 * A canonical, image independent geometry (contract {@code visual-geometry/1} Sections 3.3 and 4.2): its bounding
 * box is centered on {@code (0.5, 0.5)}, its larger extent is {@code 1}, its rotation is removed and every parameter
 * is snapped to the {@code 2^-12} grid.
 *
 * <p>A geometry never changes with position, scale, rotation, color, alpha, palette or texture: those live on the
 * {@link Transform2D pose} and, later, on an appearance. It carries no name or class beyond the record kind of its
 * primitive. Equality is exact on the canonical primitive; similarity is a metric and never an identity. The 64x64
 * {@link #canonicalMask() witness mask} is derived at construction and is not part of equality.
 *
 * <p>The only way to obtain one is {@link #canonicalize(ShapePrimitive)}. Instances are immutable and safe to share
 * across threads, and Neuron keeps no registry of them: reuse on exact equality is a caller owned map.
 */
public final class VisualGeometry {

    static final int WITNESS_SIZE = 64;
    private static final Transform2D IDENTITY = new Transform2D(0, 0, 1, 0);

    private final ShapePrimitive primitive;
    private final BinaryMask canonicalMask;

    private VisualGeometry(ShapePrimitive primitive) {
        this.primitive = primitive;
        this.canonicalMask = new CenterRuleRasterizer().render(primitive, IDENTITY, WITNESS_SIZE);
    }

    /**
     * Centers, scales and unrotates a shape, snaps it to the grid, and returns the canonical geometry with the pose
     * that maps it back onto the input.
     *
     * <p>A circle becomes {@code Circle(0.5, 0.5, 0.5)} and a square {@code Square(0.5, 0.5, 1, 0)}. A triangle is
     * turned so its longest input edge lies horizontal at the bottom with the third vertex above; an exact tie goes
     * to the lexicographically smallest snapped vertex sequence, then to the lowest stored edge. A composite is
     * normalized as a whole over the bounding box of its parts and is never re oriented; parts or holes that snap to
     * the same shape are kept once. Composite canonicalization is deterministic but not idempotent in general.
     *
     * <p>Rejections, first match wins: {@link VisualRejectionReason#CAP_EXCEEDED} for more than 16 parts or 16
     * holes; {@link VisualRejectionReason#TRANSFORM_OUT_OF_RANGE} when the pose translation would leave
     * {@code [-1, 2]}; {@link VisualRejectionReason#BELOW_GRID_RESOLUTION} when a snapped feature collapses, a
     * snapped triangle is not a fixed point of this method, or a composite hole is so far from its parts, or so much
     * larger than them, that it lands beyond the {@code 2^28} numerator bound after rescaling.
     *
     * <p>Two cases surprise callers. A triangle whose two longest edges are exactly equal (a tall isosceles) is
     * rejected with {@code BELOW_GRID_RESOLUTION}, at any size, when snapping leaves the edge that did not become
     * the base longer than the base; moving one vertex by a grid step breaks the tie. The translation range is
     * tested on the value computed in {@code double}, so a rotated shape whose exact pose translation lies on
     * {@code -1} or {@code 2} can be rejected with {@code TRANSFORM_OUT_OF_RANGE}.
     *
     * @throws IllegalArgumentException when {@code primitive} is null
     */
    public static Result<Canonicalized> canonicalize(ShapePrimitive primitive) {
        if (primitive == null) {
            throw new IllegalArgumentException("primitive must not be null");
        }
        return switch (new ShapeCanonicalizer().canonicalForm(primitive)) {
            case Result.Ok<ShapeCanonicalizer.CanonicalForm>(ShapeCanonicalizer.CanonicalForm form) ->
                    new Result.Ok<>(new Canonicalized(new VisualGeometry(form.primitive()), form.pose()));
            case Result.Rejected<ShapeCanonicalizer.CanonicalForm>(VisualRejection rejection) ->
                    new Result.Rejected<>(rejection);
        };
    }

    /** The canonical primitive. */
    public ShapePrimitive primitive() {
        return primitive;
    }

    /** The primitive rendered at 64x64 with the identity pose; shared, immutable. */
    public BinaryMask canonicalMask() {
        return canonicalMask;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VisualGeometry geometry && primitive.equals(geometry.primitive);
    }

    @Override
    public int hashCode() {
        return primitive.hashCode();
    }

    @Override
    public String toString() {
        return "VisualGeometry[" + primitive + "]";
    }
}
