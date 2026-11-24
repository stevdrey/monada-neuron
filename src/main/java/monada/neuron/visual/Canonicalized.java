package monada.neuron.visual;

/**
 * The result of {@link VisualGeometry#canonicalize(ShapePrimitive)}: the canonical geometry and the pose that maps it
 * back onto the input.
 *
 * @param geometry the canonical geometry; never null
 * @param pose maps canonical points to input points; never null
 */
public record Canonicalized(VisualGeometry geometry, Transform2D pose) {

    public Canonicalized {
        if (geometry == null) {
            throw new IllegalArgumentException("geometry must not be null");
        }
        if (pose == null) {
            throw new IllegalArgumentException("pose must not be null");
        }
    }
}
