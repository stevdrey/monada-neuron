package monada.neuron.visual;

/**
 * A closed square given by its center, side and rotation about that center, positive clockwise on screen.
 *
 * <p>A square repeats every quarter turn, so the rotation is stored inside its symmetry period {@code [0, π/2)}; a
 * value outside it throws instead of being folded.
 *
 * @param cx center x, on the grid
 * @param cy center y, on the grid
 * @param side side length, on the grid and greater than zero
 * @param rotation radians in {@code [0, π/2)}, on the grid
 */
public record Square(double cx, double cy, double side, double rotation) implements ShapePrimitive {

    public Square {
        cx = DyadicGrid.requireOnGrid(cx, "cx");
        cy = DyadicGrid.requireOnGrid(cy, "cy");
        side = DyadicGrid.requireOnGrid(side, "side");
        rotation = DyadicGrid.requireOnGrid(rotation, "rotation");
        if (side <= 0) {
            throw new IllegalArgumentException("side must be greater than zero, was " + side);
        }
        if (!(rotation >= 0 && rotation < Math.PI / 2)) {
            throw new IllegalArgumentException("rotation must be in [0, π/2), was " + rotation);
        }
    }
}
