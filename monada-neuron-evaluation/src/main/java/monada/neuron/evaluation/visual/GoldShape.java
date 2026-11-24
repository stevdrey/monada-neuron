package monada.neuron.evaluation.visual;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A gold shape of the fixture catalogue, in grid numerators of {@code 1 / 4096} (contract {@code visual-geometry/1}
 * Section 8.3). It is deliberately not a type of {@code monada.neuron.visual}: the corpus is the gold those types are
 * judged against, so it shares no code with them (spec 0013, decision 1).
 */
sealed interface GoldShape {

    /** The value in the manifest grammar of Section 8.3. */
    String print();

    /** A circle; every field is a numerator of {@code 1 / 4096}. */
    record Circle(long cx, long cy, long r) implements GoldShape {

        @Override
        public String print() {
            return "circle(" + cx + "," + cy + "," + r + ")";
        }
    }

    /** A square; {@code rotation} is a numerator of {@code k / 4096} radians. */
    record Square(long cx, long cy, long side, long rotation) implements GoldShape {

        @Override
        public String print() {
            return "square(" + cx + "," + cy + "," + side + "," + rotation + ")";
        }
    }

    /** A triangle, clockwise on screen from its minimum {@code (y, x)} vertex (Section 4.8 item 2). */
    record Triangle(long x1, long y1, long x2, long y2, long x3, long y3) implements GoldShape {

        @Override
        public String print() {
            return "triangle(" + x1 + "," + y1 + "," + x2 + "," + y2 + "," + x3 + "," + y3 + ")";
        }
    }

    /** Parts minus holes, both in the canonical order of Section 4.2. */
    record Composite(List<GoldShape> parts, List<GoldShape> holes) implements GoldShape {

        public Composite {
            parts = List.copyOf(parts);
            holes = List.copyOf(holes);
        }

        @Override
        public String print() {
            return "composite(parts=[" + joined(parts) + "],holes=[" + joined(holes) + "])";
        }

        private String joined(List<GoldShape> shapes) {
            return shapes.stream().map(GoldShape::print).collect(Collectors.joining(";"));
        }
    }
}
