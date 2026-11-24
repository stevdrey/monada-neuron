package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the catalogue against a second literal copy of contract Section 8: the five seeds of Section 8.2, the 120
 * {@code (id, b, split)} triples of Sections 8.5 and 8.7, the group counts of Section 8.10 and the header hash of the
 * committed manifest. A failure here means the gold moved, which is a new manifest version, never a test to update.
 */
class FixtureCataloguePinTest {

    /** The {@code # sha256:} of the committed manifest file; it also covers the recorded columns. */
    private static final String COMMITTED_MANIFEST_SHA256 =
            "431a6053c9817457985c705fc83fb5d13aabbe5006271baebb4773409c5a1759";

    private static final Map<String, Long> SEEDS = Map.of(
            "m-neg-10", 5137362645076074028L,
            "m-neg-11", 7281893230392710761L,
            "m-neg-12", -4849810620050002153L,
            "c-deg-04", -4199152380548194853L,
            "c-deg-05", -3897877353076230856L);

    /** {@code id b split}, in the id order of contract Section 8. */
    private static final List<String> TRIPLES = List.of(
            "m-pos-01 231 train", "m-pos-02 26 test", "m-pos-03 255 train",
            "m-pos-04 215 train", "m-pos-05 1 test", "m-pos-06 106 validation",
            "m-pos-07 176 train", "m-pos-08 249 train", "m-pos-09 158 train",
            "m-pos-10 230 train", "m-pos-11 208 train", "m-pos-12 129 train",
            "m-pos-13 87 validation", "m-pos-14 196 train", "m-pos-15 130 train",
            "m-pos-16 24 test", "m-pos-17 58 test", "m-pos-18 13 test",
            "m-pos-19 250 train", "m-pos-20 82 validation", "m-pos-21 124 train",
            "m-pos-22 142 train", "m-pos-23 67 validation", "m-pos-24 68 validation",
            "m-cmp-01 104 train", "m-cmp-02 73 validation", "m-cmp-03 196 train",
            "m-cmp-04 230 train", "m-cmp-05 214 train", "m-cmp-06 14 test",
            "m-cmp-07 238 train", "m-cmp-08 192 train", "m-cmp-09 234 train",
            "m-cmp-10 153 train", "m-cmp-11 21 test", "m-cmp-12 66 validation",
            "m-cmp-13 143 train", "m-cmp-14 17 test", "m-cmp-15 17 test",
            "m-cmp-16 135 train", "m-cmp-17 225 train", "m-cmp-18 10 test",
            "m-cmp-19 168 train", "m-cmp-20 216 train", "m-cmp-21 90 validation",
            "m-cmp-22 104 train", "m-cmp-23 31 validation", "m-cmp-24 85 validation",
            "m-neg-01 185 train", "m-neg-02 95 train", "m-neg-03 127 train",
            "m-neg-04 66 test", "m-neg-05 12 test", "m-neg-06 152 train",
            "m-neg-07 5 test", "m-neg-08 194 train", "m-neg-09 195 train",
            "m-neg-10 239 train", "m-neg-11 82 validation", "m-neg-12 73 validation",
            "m-neg-13 66 validation", "m-neg-14 84 train", "m-amb-01 137 train",
            "m-amb-02 254 train", "m-amb-03 224 train", "m-amb-04 5 test",
            "m-amb-05 76 validation", "m-amb-06 68 validation", "m-amb-07 206 train",
            "m-amb-08 63 test", "m-amb-09 159 train", "m-amb-10 194 train",
            "c-flat-01 201 validation", "c-flat-02 235 train", "c-flat-03 122 test",
            "c-flat-04 222 train", "c-grad-01 50 test", "c-grad-02 139 train",
            "c-grad-03 16 test", "c-grad-04 87 validation", "c-grad-05 106 train",
            "c-grad-06 56 validation", "c-tone-01 18 test", "c-tone-02 188 train",
            "c-tone-03 163 validation", "c-tone-04 163 validation", "c-tone-05 175 train",
            "c-tone-06 184 train", "c-tone-07 216 train", "c-tone-08 65 test",
            "c-imp-01 228 train", "c-imp-02 186 validation", "c-imp-03 47 test",
            "c-chk-01 201 train", "c-chk-02 15 test", "c-chk-03 197 train",
            "c-chk-04 171 validation", "c-step-01 51 validation", "c-step-02 201 train",
            "c-step-03 218 train", "c-step-04 47 test", "c-shp-01 138 validation",
            "c-shp-02 219 train", "c-shp-03 28 test", "c-shp-04 144 validation",
            "c-shp-05 183 train", "c-shp-06 196 train", "c-shp-07 233 train",
            "c-shp-08 28 test", "c-chan-01 185 validation", "c-chan-02 241 train",
            "c-chan-03 215 train", "c-chan-04 13 test", "c-chan-05 37 test",
            "c-chan-06 153 validation", "c-deg-01 149 train", "c-deg-02 213 train",
            "c-deg-03 105 test", "c-deg-04 105 validation", "c-deg-05 144 train");

    private final FixtureCatalogue catalogue = new FixtureCatalogue();
    private final FixtureDigests digests = new FixtureDigests();
    private final FixtureRenderer renderer = new FixtureRenderer(catalogue);

    @Test
    void theCatalogueHoldsExactlyTheIdsOfTheContractInItsOrder() {
        List<String> ids = catalogue.definitions().stream().map(FixtureDefinition::id).toList();

        assertEquals(TRIPLES.stream().map(triple -> triple.split(" ")[0]).toList(), ids);
        assertEquals(120, new HashSet<>(ids).size());
        assertEquals(72, catalogue.definitions().stream().filter(d -> d.kind() == FixtureKind.MASK).count());
        assertEquals(48, catalogue.definitions().stream().filter(d -> d.kind() == FixtureKind.RGB).count());
    }

    @Test
    void everyGroupHasTheCountsOfSection810() {
        var expected = new EnumMap<FixtureGroup, int[]>(FixtureGroup.class);
        expected.put(FixtureGroup.POSITIVE, new int[] {24, 5, 5, 14});
        expected.put(FixtureGroup.COMPOSITE, new int[] {24, 5, 5, 14});
        expected.put(FixtureGroup.NEGATIVE, new int[] {14, 3, 3, 8});
        expected.put(FixtureGroup.AMBIGUOUS, new int[] {10, 2, 2, 6});
        expected.put(FixtureGroup.FLAT, new int[] {4, 1, 1, 2});
        expected.put(FixtureGroup.GRADIENT, new int[] {6, 2, 2, 2});
        expected.put(FixtureGroup.TONE, new int[] {8, 2, 2, 4});
        expected.put(FixtureGroup.IMPULSE, new int[] {3, 1, 1, 1});
        expected.put(FixtureGroup.CHECKERBOARD, new int[] {4, 1, 1, 2});
        expected.put(FixtureGroup.STEP, new int[] {4, 1, 1, 2});
        expected.put(FixtureGroup.SHAPE_RENDER, new int[] {8, 2, 2, 4});
        expected.put(FixtureGroup.CHANNEL_PROBE, new int[] {6, 2, 2, 2});
        expected.put(FixtureGroup.DEGENERATE, new int[] {5, 1, 1, 3});

        for (FixtureGroup group : FixtureGroup.values()) {
            List<FixtureDefinition> members = catalogue.definitions().stream().filter(d -> d.group() == group)
                    .toList();
            int[] counts = expected.get(group);
            assertEquals(counts[0], members.size(), group.printed());
            assertEquals(counts[1], count(members, FixtureSplit.TEST), group.printed() + " test");
            assertEquals(counts[2], count(members, FixtureSplit.VALIDATION), group.printed() + " validation");
            assertEquals(counts[3], count(members, FixtureSplit.TRAIN), group.printed() + " train");
        }
    }

    private long count(List<FixtureDefinition> members, FixtureSplit split) {
        return members.stream().filter(d -> renderer.split(d.id()) == split).count();
    }

    @Test
    void theSeedsAreTheFirstEightBytesOfTheIdHash() {
        SEEDS.forEach((id, seed) -> assertEquals(seed, digests.seed(id), id));
    }

    @Test
    void everySplitByteAndSplitEqualsTheContractTable() {
        for (String triple : TRIPLES) {
            String[] parts = triple.split(" ");
            assertEquals(Integer.parseInt(parts[1]), digests.splitByte(parts[0]), parts[0] + " b");
            assertEquals(parts[2], renderer.split(parts[0]).printed(), parts[0] + " split");
        }
    }

    @Test
    void theCatalogueCoversOcclusionDegenerateMasksAOnePixelBorderHolesAndDisconnectedParts() {
        assertEquals(2, definition("c-shp-05").layers().size());
        assertEquals(3, composite("m-cmp-01").parts().size());
        for (String id : List.of("m-neg-01", "m-neg-02", "m-neg-03")) {
            assertEquals(FixtureGroup.NEGATIVE, definition(id).group());
        }
        assertEquals("border()", definition("m-neg-04").formula());
        for (String id : List.of("m-cmp-05", "m-cmp-06", "m-cmp-07", "m-cmp-08", "m-cmp-09")) {
            assertEquals(1, composite(id).holes().size(), id);
        }
        for (String id : List.of("m-cmp-10", "m-cmp-11", "m-cmp-12")) {
            assertEquals(2, composite(id).parts().size(), id);
            assertTrue(composite(id).holes().isEmpty(), id);
        }
    }

    private FixtureDefinition definition(String id) {
        return catalogue.find(id).orElseThrow();
    }

    private GoldShape.Composite composite(String id) {
        return (GoldShape.Composite) definition(id).shape().orElseThrow();
    }

    @Test
    void anRgbIdCarriesOnlyGoldLayersAndAMaskIdNone() {
        for (FixtureDefinition definition : catalogue.definitions()) {
            if (definition.kind() == FixtureKind.RGB) {
                assertEquals("NONE", definition.goldGeometry(), definition.id());
                assertEquals("NONE", definition.goldTransform(), definition.id());
                assertEquals("NONE", definition.goldAccept(), definition.id());
                assertEquals("NONE", definition.goldAbstain(), definition.id());
                assertEquals(definition.group() != FixtureGroup.SHAPE_RENDER,
                        definition.goldLayers().equals("NONE"), definition.id());
            } else {
                assertEquals("NONE", definition.goldLayers(), definition.id());
                assertTrue(definition.goldAccept().startsWith("{"), definition.id());
            }
            assertTrue(definition.formula().matches("[a-z_0-9]+\\([!-~]*\\)"), definition.formula());
        }
        assertEquals("{}", definition("m-neg-01").goldAccept());
        assertEquals("{EMPTY}", definition("m-neg-01").goldAbstain());
        assertEquals("{circle,square}", definition("m-amb-01").goldAccept());
        assertEquals("{AMBIGUOUS,NO_PRIMITIVE_ABOVE_MARGIN}", definition("m-amb-01").goldAbstain());
    }

    /** Section 4.8 items 9 and 10: every pose is the base pose composed with its delta. */
    @Test
    void thePoseLiteralsEqualTheBasePoseComposedWithTheirDeltas() {
        double base = 0.75;
        assertPose("m-pos-09", 0.0, 0.0, base, 0.0);
        assertPose("m-pos-10", 0.2, -0.1, base, 0.0);
        assertPose("m-pos-11", 0.0, 0.0, base * 0.5, 0.0);
        assertPose("m-pos-12", 0.0, 0.0, base * 0.25, 0.0);
        assertPose("m-pos-13", 0.0, 0.0, base, Math.toRadians(30));
        assertPose("m-pos-14", 0.0, 0.0, base, Math.toRadians(45));
        assertPose("m-pos-15", 0.1, 0.1, base * 0.5, Math.toRadians(15));
        assertPose("m-pos-16", 0.4, 0.0, base, 0.0);
        assertPose("m-pos-05", 0.0, 0.0, base * 0.9, 0.0);
        assertPose("m-pos-06", -0.3, 0.3, base, 0.0);
        assertPose("m-pos-07", 0.1, 0.1, base * 0.5, 0.0);
        assertPose("m-cmp-04", 0.0, 0.0, base, Math.toRadians(30));
        assertPose("m-amb-08", 0.0, 0.0, 1.0, 0.0);
    }

    private void assertPose(String id, double tx, double ty, double scale, double rotation) {
        assertEquals(new GoldTransform(tx, ty, scale, rotation), definition(id).transform().orElseThrow(), id);
    }

    /** Section 8.8: each duck layer is a part of the canonical duck, canonicalized alone and composed with pose 1. */
    @Test
    void theDuckLayersAreThePartsOfTheDuckDecompositionAtPoseOne() {
        List<GoldLayer> layers = definition("c-shp-01").layers();
        var body = (GoldShape.Circle) composite("m-cmp-01").parts().get(2);
        var head = (GoldShape.Circle) composite("m-cmp-01").parts().get(0);
        var beak = (GoldShape.Triangle) composite("m-cmp-01").parts().get(1);

        assertEquals(circleLayerPose(body), layers.get(0).transform());
        assertEquals(circleLayerPose(head), layers.get(1).transform());
        double leg = (beak.x2() - beak.x1()) / 4096.0;
        double bx = beak.x3() / 4096.0;
        double by = beak.y3() / 4096.0;
        assertEquals(new GoldTransform(0.75 * (bx + leg / 4 - 0.5), 0.75 * (by - leg / 4 - 0.5),
                0.75 * leg * StrictMath.sqrt(2.0), Math.toRadians(-135)), layers.get(2).transform());
    }

    private GoldTransform circleLayerPose(GoldShape.Circle part) {
        return new GoldTransform(0.75 * (part.cx() / 4096.0 - 0.5), 0.75 * (part.cy() / 4096.0 - 0.5),
                0.75 * (2 * part.r() / 4096.0), 0.0);
    }

    @Test
    void theCommittedManifestFileHasThePinnedHeaderHash() throws IOException {
        String text;
        try (InputStream in = VisualFixtureCorpus.class.getResourceAsStream(VisualFixtureCorpus.MANIFEST_RESOURCE)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] lines = text.split("\n", -1);
        int bodyStart = lines[0].length() + lines[1].length() + lines[2].length() + 3;

        assertEquals("# sha256: " + COMMITTED_MANIFEST_SHA256, lines[2]);
        assertEquals(COMMITTED_MANIFEST_SHA256,
                digests.sha256Hex(text.substring(bodyStart).getBytes(StandardCharsets.UTF_8)));
        assertTrue(text.endsWith("\n") && !text.contains("\r"), "LF line ends");
        assertEquals(4 + 240 + 1, lines.length);
    }
}
