// ABOUTME: Keeps the gold of the visual fixture corpus independent of the code it judges (issue #217).
// ABOUTME: Fails when a compiled main class of this package refers to a type of monada.neuron.visual.
package monada.neuron.evaluation.visual;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corpus is the gold that {@code monada.neuron.visual} is checked against, so its catalogue, renderers and loader
 * may not use those types: {@code VisualGeometryAgreementTest} would then compare that code with itself. The module
 * depends on the core, so such an import compiles. The check reads the compiled classes, where every reference to a
 * core visual type appears in the constant pool by its internal name.
 */
class EvaluationVisualBoundaryTest {

    private static final String PACKAGE_DIRECTORY = "monada/neuron/evaluation/visual";
    private static final String CORE_VISUAL_INTERNAL_NAME = "monada/neuron/visual/";

    /** The class files of this package under a classes root that mention a core visual type, as relative paths. */
    private List<String> offenders(Path classesRoot) throws IOException {
        Path packageRoot = classesRoot.resolve(PACKAGE_DIRECTORY);
        try (Stream<Path> files = Files.walk(packageRoot)) {
            return files.filter(file -> file.toString().endsWith(".class")).sorted()
                    .filter(this::mentionsCoreVisual)
                    .map(file -> packageRoot.relativize(file).toString())
                    .toList();
        }
    }

    private boolean mentionsCoreVisual(Path file) {
        try {
            // ISO 8859 1 maps every byte to one char, so an ASCII constant pool entry is found unchanged.
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
                    .contains(CORE_VISUAL_INTERNAL_NAME);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void noMainClassOfTheCorpusRefersToACoreVisualType() throws IOException, URISyntaxException {
        Path classesRoot = Paths.get(
                VisualFixtureCorpus.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path packageRoot = classesRoot.resolve(PACKAGE_DIRECTORY);
        for (String renderer : List.of("MaskRenderer.class", "ColorRenderer.class", "FixtureRenderer.class")) {
            assertTrue(Files.isRegularFile(packageRoot.resolve(renderer)),
                    "the scan must see " + renderer + " under " + classesRoot);
        }

        assertEquals(List.of(), offenders(classesRoot));
    }

    @Test
    void aClassThatRefersToACoreVisualTypeFailsTheScan(@TempDir Path classesRoot) throws IOException {
        Path packageRoot = Files.createDirectories(classesRoot.resolve(PACKAGE_DIRECTORY));
        Files.write(packageRoot.resolve("Clean.class"),
                "Êþº¾ monada/neuron/evaluation/visual/GoldShape java/util/List"
                        .getBytes(StandardCharsets.ISO_8859_1));
        Files.write(packageRoot.resolve("Leaking.class"),
                "Êþº¾ monada/neuron/visual/BinaryMask".getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(List.of("Leaking.class"), offenders(classesRoot));
    }
}
