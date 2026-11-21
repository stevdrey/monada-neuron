package monada.neuron.visual;

/**
 * A closed disc: a point is inside when its squared distance to the center is at most {@code r * r}.
 *
 * @param cx center x, on the grid
 * @param cy center y, on the grid
 * @param r radius, on the grid and greater than zero
 */
public record Circle(double cx, double cy, double r) implements ShapePrimitive {

    public Circle {
        cx = DyadicGrid.requireOnGrid(cx, "cx");
        cy = DyadicGrid.requireOnGrid(cy, "cy");
        r = DyadicGrid.requireOnGrid(r, "r");
        if (r <= 0) {
            throw new IllegalArgumentException("r must be greater than zero, was " + r);
        }
    }
}
