package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the single use test split (contract Section 8.10). A test source outside {@link #ALLOWED} fails this scan
 * when it contains a test split id (one that {@link #ALLOWED_IDS} does not grant it), the token {@code FixtureSplit.TEST} or the name of the all splits entry point,
 * anywhere in the file, comments included. The scan matches text: it stops accidents, not intent.
 */
class FixtureLeakageScanTest {

    /**
     * Files that may name the test split, relative to the repository root. A later issue adds its file here in the
     * same pull request that needs it, where a reviewer sees it.
     */
    private static final List<String> ALLOWED = List.of(
            "monada-neuron-evaluation/src/test/java/monada/neuron/evaluation/visual/CompositeGoldDerivationTest.java",
            "monada-neuron-evaluation/src/test/java/monada/neuron/evaluation/visual/FixtureCataloguePinTest.java",
            "monada-neuron-evaluation/src/test/java/monada/neuron/evaluation/visual/FixtureLeakageScanTest.java",
            "monada-neuron-evaluation/src/test/java/monada/neuron/evaluation/visual/VisualFixtureReplayTest.java");

    /**
     * Files that may name some test split ids and nothing else of the test split, relative to the repository root.
     * Issue #143's conformance test pins the witness mask of every gold composite, five of them in the test split.
     * It builds them from its own copy of the gold, selects nothing and scores no algorithm, which is the conformance
     * exemption of Section 8.10. Any other test split id, the split token or the all splits entry point still fails
     * there.
     */
    private static final Map<String, Set<String>> ALLOWED_IDS = Map.of(
            "src/test/java/monada/neuron/visual/VisualGeometryConformanceTest.java",
            Set.of("m-cmp-06", "m-cmp-11", "m-cmp-14", "m-cmp-15", "m-cmp-18"));

    private static final String SCAN_ROOTS_PROPERTY = "visualFixtures.scanRoots";

    private final VisualFixtureCorpus corpus = VisualFixtureCorpus.open();
    private final FixtureCatalogue catalogue = new FixtureCatalogue();

    private Set<String> forbiddenTokens() {
        var tokens = new TreeSet<String>();
        tokens.add("FixtureSplit.TEST");
        tokens.add("loadEverySplitForLosslessChecks");
        corpus.manifest().rows().stream().filter(row -> row.split() == FixtureSplit.TEST).map(ManifestRow::id)
                .forEach(tokens::add);
        return tokens;
    }

    /** Every {@code file: token} hit under the roots, outside the allow list. */
    private List<String> offenders(List<Path> roots, Set<String> tokens) throws IOException {
        var offenders = new ArrayList<String>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    if (ALLOWED.stream().anyMatch(allowed -> file.endsWith(Path.of(allowed)))) {
                        continue;
                    }
                    Set<String> permitted = ALLOWED_IDS.entrySet().stream()
                            .filter(entry -> file.endsWith(Path.of(entry.getKey()))).map(Map.Entry::getValue)
                            .findFirst().orElse(Set.of());
                    String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    for (String token : tokens) {
                        if (!permitted.contains(token) && text.contains(token)) {
                            offenders.add(file + ": " + token);
                        }
                    }
                }
            }
        }
        return offenders;
    }

    @Test
    void noTestSourceOutsideTheAllowListNamesTheTestSplit() throws IOException {
        String property = System.getProperty(SCAN_ROOTS_PROPERTY);
        assertTrue(property != null && !property.isBlank(),
                "the Gradle test task sets " + SCAN_ROOTS_PROPERTY + " from the project layout");
        List<Path> roots = Arrays.stream(property.split(File.pathSeparator)).map(Path::of).toList();

        assertTrue(roots.stream().anyMatch(root -> Files.isRegularFile(root.resolve(
                "monada/neuron/evaluation/visual/FixtureLeakageScanTest.java"))), "the scan reads this module");
        assertEquals(List.of(), offenders(roots, forbiddenTokens()));
    }

    @Test
    void theScanCoversTheTestSplitIdsTheSplitTokenAndTheAllSplitsEntryPoint() {
        Set<String> tokens = forbiddenTokens();

        assertEquals(28 + 2, tokens.size());
        assertTrue(tokens.contains("m-pos-02"));
        assertFalse(tokens.contains("m-pos-01"));
    }

    @Test
    void aTestSourceThatNamesATestSplitIdFailsTheScan(@TempDir Path root) throws IOException {
        Path clean = root.resolve("CleanTest.java");
        Path leaking = root.resolve("nested/LeakingTest.java");
        Files.createDirectories(leaking.getParent());
        Files.writeString(clean, "class CleanTest { String id = \"m-pos-01\"; }\n");
        Files.writeString(leaking, "class LeakingTest {\n    // scored on m-pos-02\n}\n");

        assertEquals(List.of(leaking + ": m-pos-02"), offenders(List.of(root), forbiddenTokens()));

        Files.writeString(leaking, "class LeakingTest { Object split = FixtureSplit.TEST; }\n");
        assertEquals(List.of(leaking + ": FixtureSplit.TEST"), offenders(List.of(root), forbiddenTokens()));

        Files.writeString(leaking, "class LeakingTest { Object all = corpus.loadEverySplitForLosslessChecks(); }\n");
        assertEquals(List.of(leaking + ": loadEverySplitForLosslessChecks"),
                offenders(List.of(root), forbiddenTokens()));
    }

    @Test
    void theConformanceTestMayNameItsFiveCompositeIdsAndNothingElseOfTheTestSplit(@TempDir Path root)
            throws IOException {
        // covers: AC-6
        Path conformance = root.resolve("src/test/java/monada/neuron/visual/VisualGeometryConformanceTest.java");
        Files.createDirectories(conformance.getParent());
        Files.writeString(conformance, "class VisualGeometryConformanceTest {\n"
                + "    // m-cmp-06 m-cmp-11 m-cmp-14 m-cmp-15 m-cmp-18\n}\n");

        assertEquals(List.of(), offenders(List.of(root), forbiddenTokens()));

        Files.writeString(conformance, "class VisualGeometryConformanceTest {\n"
                + "    // m-cmp-06 and m-pos-02\n    Object split = FixtureSplit.TEST;\n}\n");
        assertEquals(List.of(conformance + ": FixtureSplit.TEST", conformance + ": m-pos-02"),
                offenders(List.of(root), forbiddenTokens()));
    }

    /**
     * Contract Section 8.8: a layer that reuses a mask id's gold geometry and transform refers to a train mask id, and
     * a texture shares the split of the shape render id that uses it, so no held out fixture reappears in another
     * split through a layer.
     */
    @Test
    void aShapeRenderLayerNeverCarriesAHeldOutFixtureIntoAnotherSplit() {
        var renderer = new FixtureRenderer(catalogue);
        var reused = new HashSet<String>();
        for (FixtureDefinition render : catalogue.definitions()) {
            for (GoldLayer layer : render.layers()) {
                for (FixtureDefinition mask : catalogue.definitions()) {
                    if (mask.shape().equals(java.util.Optional.of(layer.geometry()))
                            && mask.transform().equals(java.util.Optional.of(layer.transform()))) {
                        reused.add(mask.id());
                        assertEquals(FixtureSplit.TRAIN, renderer.split(mask.id()),
                                render.id() + " reuses " + mask.id());
                    }
                }
                if (layer.fill() instanceof GoldLayer.Texture texture) {
                    assertEquals(renderer.split(render.id()), renderer.split(texture.fixtureId()),
                            render.id() + " uses texture " + texture.fixtureId());
                }
            }
        }
        assertTrue(reused.containsAll(List.of("m-pos-01", "m-pos-09", "m-pos-21", "m-cmp-05")),
                reused.toString());
    }
}
