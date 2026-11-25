package monada.neuron.evaluation.visual;

/**
 * A gold pose: the similarity transform of contract Section 4.7, held as the exact doubles the manifest prints.
 *
 * @param tx translation along x, in unit square units
 * @param ty translation along y, in unit square units
 * @param scale uniform scale about the center {@code (0.5, 0.5)}
 * @param rotation radians, positive clockwise on screen
 */
record GoldTransform(double tx, double ty, double scale, double rotation) {

    /** The value in the manifest grammar of Section 8.3: four {@link Double#toHexString} values. */
    String print() {
        return "transform(" + Double.toHexString(tx) + "," + Double.toHexString(ty) + "," + Double.toHexString(scale)
                + "," + Double.toHexString(rotation) + ")";
    }
}
