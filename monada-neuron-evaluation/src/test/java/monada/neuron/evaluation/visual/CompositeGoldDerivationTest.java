package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Recomputes the snapped numerators of every composite gold, and of {@code m-amb-09} and {@code m-amb-10}, from the
 * unsnapped decompositions of contract Section 8.5, without any type of {@code monada.neuron.visual} (spec 0013,
 * AC-11). The box is the union of the parts' boxes; a position {@code p} becomes {@code (p - m) / s + 0.5} and a
 * length {@code l} becomes {@code l / s}, computed in {@link BigDecimal}, converted to {@code double} once and snapped
 * with {@code Math.rint(v * 4096)}.
 */
class CompositeGoldDerivationTest {

    private static final MathContext DIVISION = MathContext.DECIMAL128;
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal TWO = new BigDecimal("2");

    /** A part or hole in unit square coordinates, before canonicalization. */
    private sealed interface Unsnapped {
    }

    private record Circle(BigDecimal cx, BigDecimal cy, BigDecimal r) implements Unsnapped {
    }

    private record Square(BigDecimal cx, BigDecimal cy, BigDecimal side) implements Unsnapped {
    }

    private record Triangle(BigDecimal x1, BigDecimal y1, BigDecimal x2, BigDecimal y2, BigDecimal x3,
                            BigDecimal y3) implements Unsnapped {
    }

    private record Decomposition(List<Unsnapped> parts, List<Unsnapped> holes) {
    }

    /** The box of the parts: its center and its larger extent. */
    private record Frame(BigDecimal mx, BigDecimal my, BigDecimal s) {

        long position(BigDecimal p, BigDecimal m) {
            return snap(p.subtract(m).divide(s, DIVISION).add(HALF));
        }

        long x(BigDecimal p) {
            return position(p, mx);
        }

        long y(BigDecimal p) {
            return position(p, my);
        }

        long length(BigDecimal l) {
            return snap(l.divide(s, DIVISION));
        }

        private long snap(BigDecimal value) {
            return (long) Math.rint(value.doubleValue() * 4096);
        }
    }

    private final FixtureCatalogue catalogue = new FixtureCatalogue();

    private static Circle circle(String cx, String cy, String r) {
        return new Circle(new BigDecimal(cx), new BigDecimal(cy), new BigDecimal(r));
    }

    private static Square square(String cx, String cy, String side) {
        return new Square(new BigDecimal(cx), new BigDecimal(cy), new BigDecimal(side));
    }

    private static Decomposition of(List<Unsnapped> parts, List<Unsnapped> holes) {
        return new Decomposition(parts, holes);
    }

    /** Two circles of radius {@code r} on the line {@code y = 0.5}, the first with its left edge at {@code x = 0}. */
    private static List<Unsnapped> pair(String r, String gap) {
        BigDecimal radius = new BigDecimal(r);
        BigDecimal second = radius.multiply(new BigDecimal("3")).add(new BigDecimal(gap));
        return List.of(new Circle(radius, HALF, radius), new Circle(second, HALF, radius));
    }

    /** Circles of radius {@code 0.12}, {@code 0.2} and {@code 0.3} stacked from the top, edge to edge gap {@code gap}. */
    private static List<Unsnapped> snowman(String gap) {
        BigDecimal g = new BigDecimal(gap);
        BigDecimal top = new BigDecimal("0.12");
        BigDecimal middle = new BigDecimal("0.24").add(g).add(new BigDecimal("0.2"));
        BigDecimal bottom = new BigDecimal("0.64").add(g).add(g).add(new BigDecimal("0.3"));
        return List.of(new Circle(HALF, top, top), new Circle(HALF, middle, new BigDecimal("0.2")),
                new Circle(HALF, bottom, new BigDecimal("0.3")));
    }

    private static List<Unsnapped> dumbbell(String gap) {
        var parts = new ArrayList<>(pair("0.2", gap));
        BigDecimal g = new BigDecimal(gap);
        parts.add(new Square(new BigDecimal("0.4").add(g.divide(TWO)), HALF, g));
        return parts;
    }

    /** Two squares side by side from {@code x = 0}, sharing {@code cy = 0.5}. */
    private static List<Unsnapped> touching(String left, String right) {
        BigDecimal a = new BigDecimal(left);
        BigDecimal b = new BigDecimal(right);
        return List.of(new Square(a.divide(TWO), HALF, a), new Square(a.add(b.divide(TWO)), HALF, b));
    }

    /** The decompositions of contract Section 8.5, by id. */
    private static Map<String, Decomposition> decompositions() {
        var unit = square("0.5", "0.5", "1");
        var disc = circle("0.5", "0.5", "0.5");
        var duck = of(List.of(circle("0.45", "0.6", "0.3"), circle("0.7", "0.35", "0.18"),
                new Triangle(new BigDecimal("0.825"), new BigDecimal("0.325"), new BigDecimal("0.975"),
                        new BigDecimal("0.475"), new BigDecimal("0.825"), new BigDecimal("0.475"))), List.of());
        var all = new LinkedHashMap<String, Decomposition>();
        all.put("m-cmp-01", duck);
        all.put("m-cmp-02", duck);
        all.put("m-cmp-03", duck);
        all.put("m-cmp-04", duck);
        all.put("m-cmp-05", of(List.of(disc), List.of(circle("0.5", "0.5", "0.15"))));
        all.put("m-cmp-06", of(List.of(disc), List.of(circle("0.5", "0.5", "0.3"))));
        all.put("m-cmp-07", of(List.of(disc), List.of(circle("0.5", "0.5", "0.45"))));
        all.put("m-cmp-08", of(List.of(unit), List.of(square("0.5", "0.5", "0.5"))));
        all.put("m-cmp-09", of(List.of(unit), List.of(square("0.5", "0.5", "0.9"))));
        all.put("m-cmp-10", of(pair("0.2", "0.05"), List.of()));
        all.put("m-cmp-11", of(pair("0.2", "0.2"), List.of()));
        all.put("m-cmp-12", of(pair("0.2", "0.4"), List.of()));
        all.put("m-cmp-15", of(snowman("0"), List.of()));
        all.put("m-cmp-16", of(snowman("0.05"), List.of()));
        all.put("m-cmp-17", of(List.of(unit), List.of(square("0.5", "0.05", "0.1"))));
        all.put("m-cmp-18", of(List.of(unit), List.of(square("0.5", "0.15", "0.3"))));
        all.put("m-cmp-19", of(List.of(unit), List.of(square("0.65", "0.35", "0.7"))));
        all.put("m-cmp-20", of(List.of(unit), List.of(square("0.75", "0.25", "0.5"))));
        all.put("m-cmp-21", of(dumbbell("0.2"), List.of()));
        all.put("m-cmp-22", of(dumbbell("0.4"), List.of()));
        all.put("m-cmp-23", of(touching("0.4", "0.4"), List.of()));
        all.put("m-cmp-24", of(touching("0.5", "0.3"), List.of()));
        all.put("m-amb-09", of(List.of(circle("0.35", "0.5", "0.3"), circle("0.65", "0.5", "0.3")), List.of()));
        all.put("m-amb-10", of(List.of(circle("0.25", "0.5", "0.3"), circle("0.75", "0.5", "0.3")), List.of()));
        return all;
    }

    private Frame frame(List<Unsnapped> parts) {
        BigDecimal minX = null;
        BigDecimal maxX = null;
        BigDecimal minY = null;
        BigDecimal maxY = null;
        for (Unsnapped part : parts) {
            BigDecimal[] box = switch (part) {
                case Circle c -> new BigDecimal[] {c.cx().subtract(c.r()), c.cx().add(c.r()),
                        c.cy().subtract(c.r()), c.cy().add(c.r())};
                case Square s -> new BigDecimal[] {s.cx().subtract(s.side().divide(TWO)),
                        s.cx().add(s.side().divide(TWO)), s.cy().subtract(s.side().divide(TWO)),
                        s.cy().add(s.side().divide(TWO))};
                case Triangle t -> new BigDecimal[] {t.x1().min(t.x2()).min(t.x3()), t.x1().max(t.x2()).max(t.x3()),
                        t.y1().min(t.y2()).min(t.y3()), t.y1().max(t.y2()).max(t.y3())};
            };
            minX = minX == null ? box[0] : minX.min(box[0]);
            maxX = maxX == null ? box[1] : maxX.max(box[1]);
            minY = minY == null ? box[2] : minY.min(box[2]);
            maxY = maxY == null ? box[3] : maxY.max(box[3]);
        }
        return new Frame(minX.add(maxX).divide(TWO), minY.add(maxY).divide(TWO),
                maxX.subtract(minX).max(maxY.subtract(minY)));
    }

    private GoldShape snapped(Unsnapped shape, Frame frame) {
        return switch (shape) {
            case Circle c -> new GoldShape.Circle(frame.x(c.cx()), frame.y(c.cy()), frame.length(c.r()));
            case Square s -> new GoldShape.Square(frame.x(s.cx()), frame.y(s.cy()), frame.length(s.side()), 0);
            case Triangle t -> new GoldShape.Triangle(frame.x(t.x1()), frame.y(t.y1()), frame.x(t.x2()),
                    frame.y(t.y2()), frame.x(t.x3()), frame.y(t.y3()));
        };
    }

    /** Section 4.2: by {@code cy}, {@code cx}, kind ordinal, then the remaining parameters; keys are doubled. */
    private long[] orderKey(GoldShape shape) {
        return switch (shape) {
            case GoldShape.Circle c -> new long[] {2 * c.cy(), 2 * c.cx(), 0, c.r()};
            case GoldShape.Square s -> new long[] {2 * s.cy(), 2 * s.cx(), 1, s.side(), s.rotation()};
            case GoldShape.Triangle t -> new long[] {
                    Math.min(t.y1(), Math.min(t.y2(), t.y3())) + Math.max(t.y1(), Math.max(t.y2(), t.y3())),
                    Math.min(t.x1(), Math.min(t.x2(), t.x3())) + Math.max(t.x1(), Math.max(t.x2(), t.x3())),
                    2, t.x1(), t.y1(), t.x2(), t.y2(), t.x3(), t.y3()};
            case GoldShape.Composite ignored -> throw new IllegalArgumentException("a part is never a composite");
        };
    }

    private List<GoldShape> canonical(List<Unsnapped> shapes, Frame frame) {
        return shapes.stream().map(shape -> snapped(shape, frame))
                .sorted(Comparator.comparing(this::orderKey, Arrays::compare)).toList();
    }

    private GoldShape gold(String id) {
        return catalogue.find(id).orElseThrow().shape().orElseThrow();
    }

    @Test
    void everyCompositeGoldIsItsDecompositionCenteredScaledAndSnapped() {
        decompositions().forEach((id, decomposition) -> {
            Frame frame = frame(decomposition.parts());
            var derived = new GoldShape.Composite(canonical(decomposition.parts(), frame),
                    canonical(decomposition.holes(), frame));

            assertEquals(gold(id).print(), derived.print(), id);
        });
    }

    /** Section 4.8 item 6: the hole is the catalogue {@code T1} scaled about its bounding box center. */
    @Test
    void theHoleOfATriangleFrameIsTheCatalogueTriangleScaledAboutItsCenter() {
        var t1 = (GoldShape.Triangle) gold("m-pos-17");
        assertEquals("triangle(2048,275,4096,3821,0,3821)", t1.print());

        for (var frame : Map.of("m-cmp-13", "0.3", "m-cmp-14", "0.6").entrySet()) {
            var composite = (GoldShape.Composite) gold(frame.getKey());
            BigDecimal extent = new BigDecimal(frame.getValue());
            var hole = new GoldShape.Triangle(scaled(t1.x1(), extent), scaled(t1.y1(), extent),
                    scaled(t1.x2(), extent), scaled(t1.y2(), extent), scaled(t1.x3(), extent),
                    scaled(t1.y3(), extent));

            assertEquals(List.of(t1), composite.parts(), frame.getKey());
            assertEquals(List.of(hole), composite.holes(), frame.getKey());
        }
    }

    private long scaled(long numerator, BigDecimal extent) {
        BigDecimal center = new BigDecimal(2048);
        BigDecimal value = center.add(new BigDecimal(numerator).subtract(center).multiply(extent))
                .divide(new BigDecimal(4096), DIVISION);
        return (long) Math.rint(value.doubleValue() * 4096);
    }

    @Test
    void theCatalogueHasNoCompositeWithoutADerivation() {
        long composites = catalogue.definitions().stream()
                .filter(definition -> definition.shape().orElse(null) instanceof GoldShape.Composite).count();

        assertEquals(composites, decompositions().size() + 2);
    }

    /** The pose {@code canonicalize} returns for an absolute decomposition has the box's larger extent as scale. */
    @Test
    void theTwoOverlappingCirclesCarryTheirBoxExtentAsGoldScale() {
        for (String id : List.of("m-amb-09", "m-amb-10")) {
            Frame frame = frame(decompositions().get(id).parts());
            GoldTransform transform = catalogue.find(id).orElseThrow().transform().orElseThrow();

            assertEquals(new GoldTransform(0.0, 0.0, frame.s().doubleValue(), 0.0), transform, id);
            assertEquals(0, frame.mx().compareTo(HALF), id);
            assertEquals(0, frame.my().compareTo(HALF), id);
        }
    }

    @Test
    void everyOtherCompositeSitsAtTheBasePoseScale() {
        for (FixtureDefinition definition : catalogue.definitions()) {
            if (definition.group() == FixtureGroup.COMPOSITE && definition.pose().getAsInt() == 1) {
                assertEquals(new GoldTransform(0.0, 0.0, 0.75, 0.0), definition.transform().orElseThrow(),
                        definition.id());
            }
        }
    }
}
