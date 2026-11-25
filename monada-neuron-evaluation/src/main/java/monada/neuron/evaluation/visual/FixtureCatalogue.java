package monada.neuron.evaluation.visual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * The 120 fixture ids of {@code visual-fixtures/1} with their gold values, as literal data copied from the tables of
 * contract {@code visual-geometry/1} Sections 8.4, 8.5, 8.7 and 8.8. This catalogue is the source of the manifest's
 * gold columns; a changed value here is a new manifest version.
 */
final class FixtureCatalogue {

    /** The two raster sizes every id is generated at. */
    static final List<Integer> SIZES = List.of(32, 64);

    /** Predicate ids whose foreground touches or crosses the raster edge; they have no pose, so they are listed. */
    static final List<String> EDGE_TOUCHING = List.of("m-amb-08", "m-amb-10");

    private static final GoldShape CIRCLE = new GoldShape.Circle(2048, 2048, 2048);
    private static final GoldShape SQUARE = new GoldShape.Square(2048, 2048, 4096, 0);
    private static final GoldShape T1 = new GoldShape.Triangle(2048, 275, 4096, 3821, 0, 3821);
    private static final GoldShape T2 = new GoldShape.Triangle(2048, 1024, 4096, 3072, 0, 3072);
    private static final GoldShape T3 = new GoldShape.Triangle(1475, 1065, 4096, 3031, 0, 3031);

    private static final GoldTransform BASE = new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.8p-1, 0x0.0p0);
    private static final GoldTransform IDENTITY = new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.0p0, 0x0.0p0);

    /** Poses 1 to 8 of Section 8.4 for every shape but the circle. */
    private static final List<GoldTransform> POSES = List.of(
            BASE,
            new GoldTransform(0x1.999999999999ap-3, -0x1.999999999999ap-4, 0x1.8p-1, 0x0.0p0),
            new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.8p-2, 0x0.0p0),
            new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.8p-3, 0x0.0p0),
            new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.8p-1, 0x1.0c152382d7365p-1),
            new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.8p-1, 0x1.921fb54442d18p-1),
            new GoldTransform(0x1.999999999999ap-4, 0x1.999999999999ap-4, 0x1.8p-2, 0x1.0c152382d7365p-2),
            new GoldTransform(0x1.999999999999ap-2, 0x0.0p0, 0x1.8p-1, 0x0.0p0));

    /** Poses 1 to 8 of Section 8.4 for the circle, which replaces the two pure rotations. */
    private static final List<GoldTransform> CIRCLE_POSES = List.of(
            BASE,
            new GoldTransform(0x1.999999999999ap-3, -0x1.999999999999ap-4, 0x1.8p-1, 0x0.0p0),
            new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.8p-2, 0x0.0p0),
            new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.8p-3, 0x0.0p0),
            new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.599999999999ap-1, 0x0.0p0),
            new GoldTransform(-0x1.3333333333333p-2, 0x1.3333333333333p-2, 0x1.8p-1, 0x0.0p0),
            new GoldTransform(0x1.999999999999ap-4, 0x1.999999999999ap-4, 0x1.8p-2, 0x0.0p0),
            new GoldTransform(0x1.999999999999ap-2, 0x0.0p0, 0x1.8p-1, 0x0.0p0));

    private static final Set<String> NO_FIT = Set.of("NO_PRIMITIVE_ABOVE_MARGIN");
    private static final Set<String> UNSURE = Set.of("AMBIGUOUS", "NO_PRIMITIVE_ABOVE_MARGIN");

    private final Map<String, FixtureDefinition> byId = new LinkedHashMap<>();

    FixtureCatalogue() {
        positives();
        composites();
        negatives();
        ambiguous();
        colors();
    }

    /** Every definition, in the id order of contract Section 8. */
    List<FixtureDefinition> definitions() {
        return List.copyOf(byId.values());
    }

    /** The definition of an id. */
    Optional<FixtureDefinition> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    private void positives() {
        for (var pose = 1; pose <= 8; pose++) {
            posed("m-pos-" + two(pose), FixtureGroup.POSITIVE, CIRCLE, pose, CIRCLE_POSES, "circle");
        }
        for (var pose = 1; pose <= 8; pose++) {
            posed("m-pos-" + two(8 + pose), FixtureGroup.POSITIVE, SQUARE, pose, POSES, "square");
        }
        for (var pose = 1; pose <= 4; pose++) {
            posed("m-pos-" + two(16 + pose), FixtureGroup.POSITIVE, T1, pose, POSES, "triangle");
        }
        posed("m-pos-21", FixtureGroup.POSITIVE, T2, 5, POSES, "triangle");
        posed("m-pos-22", FixtureGroup.POSITIVE, T2, 6, POSES, "triangle");
        posed("m-pos-23", FixtureGroup.POSITIVE, T3, 7, POSES, "triangle");
        posed("m-pos-24", FixtureGroup.POSITIVE, T3, 8, POSES, "triangle");
    }

    private void composites() {
        GoldShape duck = composite(parts(circle(2731, 1130, 894), triangle(3351, 1005, 4096, 1750, 3351, 1750),
                circle(1489, 2371, 1489)), holes());
        composite("m-cmp-01", duck, 1);
        composite("m-cmp-02", duck, 2);
        composite("m-cmp-03", duck, 3);
        composite("m-cmp-04", duck, 5);
        composite("m-cmp-05", composite(parts(CIRCLE), holes(circle(2048, 2048, 614))), 1);
        composite("m-cmp-06", composite(parts(CIRCLE), holes(circle(2048, 2048, 1229))), 1);
        composite("m-cmp-07", composite(parts(CIRCLE), holes(circle(2048, 2048, 1843))), 1);
        composite("m-cmp-08", composite(parts(SQUARE), holes(square(2048, 2048, 2048))), 1);
        composite("m-cmp-09", composite(parts(SQUARE), holes(square(2048, 2048, 3686))), 1);
        composite("m-cmp-10", composite(parts(circle(964, 2048, 964), circle(3132, 2048, 964)), holes()), 1);
        composite("m-cmp-11", composite(parts(circle(819, 2048, 819), circle(3277, 2048, 819)), holes()), 1);
        composite("m-cmp-12", composite(parts(circle(683, 2048, 683), circle(3413, 2048, 683)), holes()), 1);
        composite("m-cmp-13", composite(parts(T1), holes(triangle(2048, 1516, 2662, 2580, 1434, 2580))), 1);
        composite("m-cmp-14", composite(parts(T1), holes(triangle(2048, 984, 3277, 3112, 819, 3112))), 1);
        composite("m-cmp-15", composite(parts(circle(2048, 396, 396), circle(2048, 1453, 661),
                circle(2048, 3105, 991)), holes()), 1);
        composite("m-cmp-16", composite(parts(circle(2048, 367, 367), circle(2048, 1498, 611),
                circle(2048, 3179, 917)), holes()), 1);
        composite("m-cmp-17", composite(parts(SQUARE), holes(square(2048, 205, 410))), 1);
        composite("m-cmp-18", composite(parts(SQUARE), holes(square(2048, 614, 1229))), 1);
        composite("m-cmp-19", composite(parts(SQUARE), holes(square(2662, 1434, 2867))), 1);
        composite("m-cmp-20", composite(parts(SQUARE), holes(square(3072, 1024, 2048))), 1);
        composite("m-cmp-21", composite(parts(circle(819, 2048, 819), square(2048, 2048, 819),
                circle(3277, 2048, 819)), holes()), 1);
        composite("m-cmp-22", composite(parts(circle(683, 2048, 683), square(2048, 2048, 1365),
                circle(3413, 2048, 683)), holes()), 1);
        composite("m-cmp-23", composite(parts(square(1024, 2048, 2048), square(3072, 2048, 2048)), holes()), 1);
        composite("m-cmp-24", composite(parts(square(1280, 2048, 2560), square(3328, 2048, 1536)), holes()), 1);
    }

    private void negatives() {
        negative("m-neg-01", "empty()", Set.of("EMPTY"));
        negative("m-neg-02", "full()", Set.of("FULL"));
        negative("m-neg-03", "full_except_center()", Set.of("FULL"));
        negative("m-neg-04", "border()", NO_FIT);
        negative("m-neg-05", "center_pixel()", Set.of("TOO_SMALL"));
        negative("m-neg-06", "corner_pixel()", Set.of("TOO_SMALL"));
        negative("m-neg-07", "center_row()", Set.of("TOO_SMALL"));
        negative("m-neg-08", "diagonal()", NO_FIT);
        negative("m-neg-09", "checker(1)", NO_FIT);
        negative("m-neg-10", "bernoulli(0.5)", NO_FIT);
        negative("m-neg-11", "bernoulli(0.5)", NO_FIT);
        negative("m-neg-12", "bernoulli(0.05)", NO_FIT);
        negative("m-neg-13", "center_column()", Set.of("TOO_SMALL"));
        negative("m-neg-14", "sliver()", Set.of("TOO_SMALL"));
    }

    private void ambiguous() {
        ambiguous("m-amb-01", "rounded_square(0.41)", null, null, Set.of("circle", "square"), UNSURE);
        ambiguous("m-amb-02", "rounded_square(0.45)", null, null, Set.of("circle", "square"), UNSURE);
        ambiguous("m-amb-03", "ellipse(0.375,0.3125)", null, null, Set.of("circle"), UNSURE);
        ambiguous("m-amb-04", "ellipse(0.225,0.1875)", null, null, Set.of("circle"), UNSURE);
        ambiguous("m-amb-05", "pentagon()", null, null, Set.of("circle"), UNSURE);
        ambiguous("m-amb-06", "hexagon()", null, null, Set.of("circle"), UNSURE);
        ambiguous("m-amb-07", "circle_center_hole()", CIRCLE, BASE, Set.of("circle"), Set.of());
        ambiguous("m-amb-08", "circle(0.5)", CIRCLE, IDENTITY, Set.of("circle"), Set.of());
        ambiguous("m-amb-09", "two_circles(0.35,0.65,0.3)",
                composite(parts(circle(1365, 2048, 1365), circle(2731, 2048, 1365)), holes()),
                new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.ccccccccccccdp-1, 0x0.0p0), Set.of(), UNSURE);
        ambiguous("m-amb-10", "two_circles(0.25,0.75,0.3)",
                composite(parts(circle(1117, 2048, 1117), circle(2979, 2048, 1117)), holes()),
                new GoldTransform(0x0.0p0, 0x0.0p0, 0x1.199999999999ap0, 0x0.0p0), Set.of(), UNSURE);
    }

    private void colors() {
        color("c-flat-01", FixtureGroup.FLAT, "flat(0,0,0,255)");
        color("c-flat-02", FixtureGroup.FLAT, "flat(255,255,255,255)");
        color("c-flat-03", FixtureGroup.FLAT, "flat(128,128,128,255)");
        color("c-flat-04", FixtureGroup.FLAT, "flat(255,0,0,255)");
        color("c-grad-01", FixtureGroup.GRADIENT, "gradient_x()");
        color("c-grad-02", FixtureGroup.GRADIENT, "gradient_y()");
        color("c-grad-03", FixtureGroup.GRADIENT, "gradient_diagonal()");
        color("c-grad-04", FixtureGroup.GRADIENT, "gradient_radial()");
        color("c-grad-05", FixtureGroup.GRADIENT, "gradient_rgb()");
        color("c-grad-06", FixtureGroup.GRADIENT, "gradient_alpha()");
        color("c-tone-01", FixtureGroup.TONE, "tone(1,0,0)");
        color("c-tone-02", FixtureGroup.TONE, "tone(0,1,0)");
        color("c-tone-03", FixtureGroup.TONE, "tone(3,0,0)");
        color("c-tone-04", FixtureGroup.TONE, "tone(1,1,0)");
        color("c-tone-05", FixtureGroup.TONE, "tone(1,0,pi/2)");
        color("c-tone-06", FixtureGroup.TONE, "tone(N/2,0,0)");
        color("c-tone-07", FixtureGroup.TONE, "tone_amplitude(0.25)");
        color("c-tone-08", FixtureGroup.TONE, "tone_mix()");
        color("c-imp-01", FixtureGroup.IMPULSE, "impulse_center()");
        color("c-imp-02", FixtureGroup.IMPULSE, "impulse_origin()");
        color("c-imp-03", FixtureGroup.IMPULSE, "impulse_pair()");
        color("c-chk-01", FixtureGroup.CHECKERBOARD, "checker(1)");
        color("c-chk-02", FixtureGroup.CHECKERBOARD, "checker(2)");
        color("c-chk-03", FixtureGroup.CHECKERBOARD, "checker(4)");
        color("c-chk-04", FixtureGroup.CHECKERBOARD, "checker(8)");
        color("c-step-01", FixtureGroup.STEP, "step_x(N/2)");
        color("c-step-02", FixtureGroup.STEP, "step_x(floor(0.3125N))");
        color("c-step-03", FixtureGroup.STEP, "step_y(N/4)");
        color("c-step-04", FixtureGroup.STEP, "step_diagonal()");
        shapeRenders();
        color("c-chan-01", FixtureGroup.CHANNEL_PROBE, "flat(10,0,0,255)");
        color("c-chan-02", FixtureGroup.CHANNEL_PROBE, "flat(0,255,0,255)");
        color("c-chan-03", FixtureGroup.CHANNEL_PROBE, "flat(0,0,255,255)");
        color("c-chan-04", FixtureGroup.CHANNEL_PROBE, "flat(255,0,0,0)");
        color("c-chan-05", FixtureGroup.CHANNEL_PROBE, "flat(255,0,0,128)");
        color("c-chan-06", FixtureGroup.CHANNEL_PROBE, "flat(128,128,128,128)");
        color("c-deg-01", FixtureGroup.DEGENERATE, "flat(0,0,0,0)");
        color("c-deg-02", FixtureGroup.DEGENERATE, "flat(255,255,255,0)");
        color("c-deg-03", FixtureGroup.DEGENERATE, "flat(255,255,255,1)");
        color("c-deg-04", FixtureGroup.DEGENERATE, "noise_rgb()");
        color("c-deg-05", FixtureGroup.DEGENERATE, "noise_rgba()");
    }

    /** The frozen {@code gold_layers} values of Section 8.8. */
    private void shapeRenders() {
        var green = new GoldLayer.Flat(0, 160, 0);
        var red = new GoldLayer.Flat(255, 0, 0);
        var blue = new GoldLayer.Flat(0, 0, 255);
        shapeRender("c-shp-01",
                layer(CIRCLE, new GoldTransform(-0x1.a34p-4, 0x1.e48p-5, 0x1.173p-1, 0x0.0p0), green, 0x1.0p0),
                layer(CIRCLE, new GoldTransform(0x1.002p-3, -0x1.584p-3, 0x1.4f4p-2, 0x0.0p0), green, 0x1.0p0),
                layer(T2, new GoldTransform(0x1.173cp-2, -0x1.6b3p-4, 0x1.8b188dd1b19e3p-3, -0x1.2d97c7f3321d2p1),
                        new GoldLayer.Flat(255, 200, 0), 0x1.0p0));
        shapeRender("c-shp-02", layer(CIRCLE, BASE, red, 0x1.0p0));
        shapeRender("c-shp-03", layer(SQUARE, BASE, new GoldLayer.Texture("c-grad-01"), 0x1.0p0));
        shapeRender("c-shp-04", layer(T2, POSES.get(4), blue, 0x1.0p0));
        shapeRender("c-shp-05",
                layer(CIRCLE, new GoldTransform(-0x1.999999999999ap-3, 0x0.0p0, 0x1.0p-1, 0x0.0p0), red, 0x1.0p-1),
                layer(CIRCLE, new GoldTransform(0x1.999999999999ap-3, 0x0.0p0, 0x1.0p-1, 0x0.0p0), blue, 0x1.0p-1));
        shapeRender("c-shp-06", background(new GoldLayer.Flat(200, 200, 200)), layer(CIRCLE, BASE, red, 0x1.0p0));
        shapeRender("c-shp-07", layer(SQUARE, BASE, new GoldLayer.Texture("c-chk-03"), 0x1.0p0));
        shapeRender("c-shp-08", background(new GoldLayer.Flat(0, 0, 200)),
                new GoldLayer(composite(parts(CIRCLE), holes(circle(2048, 2048, 614))), BASE,
                        Optional.of(new GoldLayer.Annotation(List.of("CIRCLE"), List.of("CIRCLE"))), red, 0x1.0p0,
                        false));
    }

    private void posed(String id, FixtureGroup group, GoldShape shape, int pose, List<GoldTransform> poses,
                       String kind) {
        add(new FixtureDefinition(id, group, "render(gold)", OptionalInt.of(pose), Optional.of(shape),
                Optional.of(poses.get(pose - 1)), new TreeSet<>(Set.of(kind)), new TreeSet<>(), List.of()));
    }

    private void composite(String id, GoldShape shape, int pose) {
        posed(id, FixtureGroup.COMPOSITE, shape, pose, POSES, "composite");
    }

    private void negative(String id, String formula, Set<String> abstain) {
        add(new FixtureDefinition(id, FixtureGroup.NEGATIVE, formula, OptionalInt.empty(), Optional.empty(),
                Optional.empty(), new TreeSet<>(), new TreeSet<>(abstain), List.of()));
    }

    private void ambiguous(String id, String formula, GoldShape shape, GoldTransform transform, Set<String> accept,
                           Set<String> abstain) {
        add(new FixtureDefinition(id, FixtureGroup.AMBIGUOUS, formula, OptionalInt.empty(),
                Optional.ofNullable(shape), Optional.ofNullable(transform), new TreeSet<>(accept),
                new TreeSet<>(abstain), List.of()));
    }

    private void color(String id, FixtureGroup group, String formula) {
        add(new FixtureDefinition(id, group, formula, OptionalInt.empty(), Optional.empty(), Optional.empty(),
                new TreeSet<>(), new TreeSet<>(), List.of()));
    }

    private void shapeRender(String id, GoldLayer... layers) {
        add(new FixtureDefinition(id, FixtureGroup.SHAPE_RENDER, "layers(gold)", OptionalInt.empty(),
                Optional.empty(), Optional.empty(), new TreeSet<>(), new TreeSet<>(), List.of(layers)));
    }

    private void add(FixtureDefinition definition) {
        if (byId.put(definition.id(), definition) != null) {
            throw new IllegalStateException("duplicate fixture id " + definition.id());
        }
    }

    private GoldLayer layer(GoldShape shape, GoldTransform transform, GoldLayer.Fill fill, double alpha) {
        return new GoldLayer(shape, transform, Optional.empty(), fill, alpha, false);
    }

    private GoldLayer background(GoldLayer.Fill fill) {
        return new GoldLayer(SQUARE, IDENTITY, Optional.empty(), fill, 0x1.0p0, true);
    }

    private GoldShape circle(long cx, long cy, long r) {
        return new GoldShape.Circle(cx, cy, r);
    }

    private GoldShape square(long cx, long cy, long side) {
        return new GoldShape.Square(cx, cy, side, 0);
    }

    private GoldShape triangle(long x1, long y1, long x2, long y2, long x3, long y3) {
        return new GoldShape.Triangle(x1, y1, x2, y2, x3, y3);
    }

    private GoldShape composite(List<GoldShape> parts, List<GoldShape> holes) {
        return new GoldShape.Composite(parts, holes);
    }

    private List<GoldShape> parts(GoldShape... shapes) {
        return List.of(shapes);
    }

    private List<GoldShape> holes(GoldShape... shapes) {
        return List.of(shapes);
    }

    private String two(int number) {
        return number < 10 ? "0" + number : Integer.toString(number);
    }
}
