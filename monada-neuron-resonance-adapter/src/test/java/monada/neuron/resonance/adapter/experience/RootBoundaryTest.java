package monada.neuron.resonance.adapter.experience;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the ownership rule: Store types never enter the root cognitive code, and the adapter confines them to one class. */
final class RootBoundaryTest {

    private static List<String> offenders(Path sources, java.util.function.Predicate<Path> include) throws IOException {
        try (Stream<Path> files = Files.walk(sources)) {
            return files.filter(p -> p.toString().endsWith(".java")).filter(include).filter(p -> {
                try {
                    return Files.readString(p).contains("com.monada.");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void noRootProductionSourceImportsStoreTypes() throws IOException {
        Path rootMain = Path.of("..", "src", "main", "java");
        assertTrue(Files.isDirectory(rootMain), "run from the adapter module: " + rootMain.toAbsolutePath());
        assertEquals(List.of(), offenders(rootMain, p -> true));
    }

    @Test
    void onlyTheTranslatorAndItsReferenceFormatterTouchStoreTypesInThisPackage() throws IOException {
        Path experience = Path.of("src", "main", "java", "monada", "neuron", "resonance", "adapter", "experience");
        assertEquals(List.of("ExperienceRefs.java", "StoreExperienceTranslator.java"), offenders(experience, p -> true));
    }

    @Test
    void noProductionSourceDependsOnTheEvaluationModule() throws IOException {
        Path experience = Path.of("src", "main", "java");
        try (Stream<Path> files = Files.walk(experience)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertTrue(!Files.readString(file).contains("com.monada.evaluation"), file.toString());
            }
        }
    }
}
