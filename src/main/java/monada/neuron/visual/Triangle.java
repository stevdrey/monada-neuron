package monada.neuron.visual;

/**
 * A closed triangle with a normalized vertex order (contract {@code visual-geometry/1} Section 4.8 item 2).
 *
 * <p>Whatever order the arguments come in, the vertices are stored clockwise on screen ({@code y} down, positive
 * shoelace sum) starting at the minimum {@code (y, x)} vertex. A counterclockwise input is reversed and then rotated
 * to that start, so {@code new Triangle(a, b, c).x1()} need not be {@code a}'s {@code x}. All six argument orders of
 * one triangle give equal records. Three collinear points have no area and throw.
 *
 * @param x1 first stored vertex x
 * @param y1 first stored vertex y
 * @param x2 second stored vertex x
 * @param y2 second stored vertex y
 * @param x3 third stored vertex x
 * @param y3 third stored vertex y
 */
public record Triangle(double x1, double y1, double x2, double y2, double x3, double y3) implements ShapePrimitive {

    public Triangle {
        x1 = DyadicGrid.requireOnGrid(x1, "x1");
        y1 = DyadicGrid.requireOnGrid(y1, "y1");
        x2 = DyadicGrid.requireOnGrid(x2, "x2");
        y2 = DyadicGrid.requireOnGrid(y2, "y2");
        x3 = DyadicGrid.requireOnGrid(x3, "x3");
        y3 = DyadicGrid.requireOnGrid(y3, "y3");

        long ax = DyadicGrid.numerator(x1);
        long ay = DyadicGrid.numerator(y1);
        long shoelace = (DyadicGrid.numerator(x2) - ax) * (DyadicGrid.numerator(y3) - ay)
                - (DyadicGrid.numerator(x3) - ax) * (DyadicGrid.numerator(y2) - ay);
        if (shoelace == 0) {
            throw new IllegalArgumentException("a triangle needs three vertices that are not collinear");
        }
        if (shoelace < 0) {
            double x = x2;
            double y = y2;
            x2 = x3;
            y2 = y3;
            x3 = x;
            y3 = y;
        }

        int start = 0;
        double minX = x1;
        double minY = y1;
        if (y2 < minY || (y2 == minY && x2 < minX)) {
            start = 1;
            minX = x2;
            minY = y2;
        }
        if (y3 < minY || (y3 == minY && x3 < minX)) {
            start = 2;
        }
        if (start != 0) {
            double firstX = x1;
            double firstY = y1;
            if (start == 1) {
                x1 = x2;
                y1 = y2;
                x2 = x3;
                y2 = y3;
                x3 = firstX;
                y3 = firstY;
            } else {
                x1 = x3;
                y1 = y3;
                x3 = x2;
                y3 = y2;
                x2 = firstX;
                y2 = firstY;
            }
        }
    }
}
