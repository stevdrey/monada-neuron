package monada.neuron.visual;

/**
 * A pose: the similarity that places a canonical geometry (contract {@code visual-geometry/1} Sections 4.1 and 4.7).
 *
 * <p>A canonical point {@code p} maps to {@code R(rotation) · (scale · (p − c)) + c + (tx, ty)} with
 * {@code c = (0.5, 0.5)} and {@code R = [cos −sin; sin cos]} in {@code y} down coordinates, so a positive rotation is
 * clockwise on screen. Size and position live here and never on a {@link VisualGeometry}. Anisotropic scale and shear
 * are not representable, because they would change the shape.
 *
 * <p>The constructor throws {@link IllegalArgumentException} for any invalid value and stores {@code -0.0} as
 * {@code +0.0}. The values are not bound to the grid. Operations that would build a pose outside the translation
 * range check first and return {@link VisualRejectionReason#TRANSFORM_OUT_OF_RANGE}.
 *
 * @param tx translation x in {@code [-1, 2]}
 * @param ty translation y in {@code [-1, 2]}
 * @param scale uniform scale, finite and greater than zero
 * @param rotation radians in {@code (-π, π]}, positive clockwise on screen
 */
public record Transform2D(double tx, double ty, double scale, double rotation) {

    static final double MIN_TRANSLATION = -1.0;
    static final double MAX_TRANSLATION = 2.0;
    /** The fixed center {@code c = (0.5, 0.5)} of every pose (Section 4.1), on both axes. */
    static final double CENTER = 0.5;

    public Transform2D {
        if (!(tx >= MIN_TRANSLATION && tx <= MAX_TRANSLATION)) {
            throw new IllegalArgumentException("tx must be in [-1, 2], was " + tx);
        }
        if (!(ty >= MIN_TRANSLATION && ty <= MAX_TRANSLATION)) {
            throw new IllegalArgumentException("ty must be in [-1, 2], was " + ty);
        }
        if (!(scale > 0 && scale < Double.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("scale must be finite and greater than zero, was " + scale);
        }
        if (!(rotation > -Math.PI && rotation <= Math.PI)) {
            throw new IllegalArgumentException("rotation must be in (-π, π], was " + rotation);
        }
        tx += 0.0;
        ty += 0.0;
        rotation += 0.0;
    }

    /**
     * Maps a canonical point to the posed input frame.
     *
     * @return a fresh {@code {x, y}} array
     */
    public double[] apply(double x, double y) {
        double cos = StrictMath.cos(rotation);
        double sin = StrictMath.sin(rotation);
        double dx = scale * (x - CENTER);
        double dy = scale * (y - CENTER);
        return new double[] {dx * cos - dy * sin + CENTER + tx, dx * sin + dy * cos + CENTER + ty};
    }

    /**
     * Maps a posed point back to the canonical frame. It is a point map, not a {@code Transform2D}, because the
     * inverse translation can leave {@code [-1, 2]} ({@code tx 1}, {@code scale 0.25} inverts to {@code tx -4}).
     *
     * @return a fresh {@code {x, y}} array
     */
    public double[] applyInverse(double x, double y) {
        double cos = StrictMath.cos(rotation);
        double sin = StrictMath.sin(rotation);
        double qx = x - CENTER - tx;
        double qy = y - CENTER - ty;
        return new double[] {(qx * cos + qy * sin) / scale + CENTER, (-qx * sin + qy * cos) / scale + CENTER};
    }
}
