package monada.neuron.evaluation.visual;

import java.util.List;
import java.util.Optional;

/**
 * One layer of a shape render id (contract Section 8.8): a gold shape at a gold pose with its appearance. Geometry and
 * color stay separate fields, so a layer never reads a color from a shape or a shape from a color.
 *
 * @param geometry the canonical gold shape
 * @param transform the gold pose
 * @param annotation the part and hole kinds of a composite layer; empty for a single primitive
 * @param fill flat sRGB bytes or the id of a texture fixture
 * @param alpha straight alpha in {@code [0, 1]}
 * @param background true for a stored, never fitted background layer
 */
record GoldLayer(GoldShape geometry, GoldTransform transform, Optional<Annotation> annotation, Fill fill,
                 double alpha, boolean background) {

    /** The value in the manifest grammar of Section 8.3. */
    String print() {
        return "layer(geometry=" + geometry.print() + ",transform=" + transform.print() + ",annotation="
                + annotation.map(Annotation::print).orElse("NONE") + ",fill=" + fill.print() + ",alpha="
                + Double.toHexString(alpha) + ",background=" + background + ")";
    }

    /** What a layer is painted with. */
    sealed interface Fill {

        /** The value in the manifest grammar of Section 8.3. */
        String print();
    }

    /** A flat sRGB color in bytes. */
    record Flat(int r, int g, int b) implements Fill {

        @Override
        public String print() {
            return "rgb(" + r + "," + g + "," + b + ")";
        }
    }

    /** The decoded RGBA image of another fixture id, taken at the same fixture size. */
    record Texture(String fixtureId) implements Fill {

        @Override
        public String print() {
            return "texture(" + fixtureId + ")";
        }
    }

    /** The kinds ({@code CIRCLE}, {@code SQUARE}, {@code TRIANGLE}) of a composite layer's parts and holes. */
    record Annotation(List<String> parts, List<String> holes) {

        public Annotation {
            parts = List.copyOf(parts);
            holes = List.copyOf(holes);
        }

        String print() {
            return "annotation(parts=[" + String.join(",", parts) + "],holes=[" + String.join(",", holes) + "])";
        }
    }
}
