package monada.neuron.visual;

import monada.neuron.Main;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 0012 AC-9: the visual package is opt in, so no other core package may refer to it. The check reads the
 * compiled classes, where every reference to a visual type appears in the constant pool by its internal name.
 */
class VisualPackageBoundaryTest {

    private static final String VISUAL_DIRECTORY = "monada/neuron/visual";
    private static final String VISUAL_INTERNAL_NAME = "monada/neuron/visual/";

    @Test
    void noCoreClassOutsideThePackageRefersToAVisualType() throws Exception {
        Path classesRoot = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Path> classFiles;
        try (Stream<Path> files = Files.walk(classesRoot)) {
            classFiles = files.filter(file -> file.toString().endsWith(".class")).sorted().toList();
        }
        List<Path> visual = classFiles.stream().filter(file -> isVisual(classesRoot, file)).toList();
        assertTrue(visual.stream().anyMatch(file -> file.endsWith("VisualGeometry.class")),
                "the scan must see the visual classes under " + classesRoot);
        assertTrue(classFiles.size() > visual.size(), "the scan must see the other core classes");

        List<String> offenders = classFiles.stream()
                .filter(file -> !isVisual(classesRoot, file))
                .filter(this::mentionsVisual)
                .map(file -> classesRoot.relativize(file).toString())
                .toList();

        assertEquals(List.of(), offenders);
    }

    private boolean isVisual(Path classesRoot, Path file) {
        return classesRoot.relativize(file).getParent() != null
                && classesRoot.relativize(file).getParent().startsWith(Paths.get(VISUAL_DIRECTORY));
    }

    private boolean mentionsVisual(Path file) {
        try {
            // ISO 8859 1 maps every byte to one char, so an ASCII constant pool entry is found unchanged.
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(VISUAL_INTERNAL_NAME);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
