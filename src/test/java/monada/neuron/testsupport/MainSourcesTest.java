// ABOUTME: Checks that MainSources resolves the main source directory from the build's system property.
// ABOUTME: A missing or wrong property must fail with a message that names the property.
package monada.neuron.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainSourcesTest {

    @Test
    void theBuildPassesAnAbsoluteDirectoryThatHoldsTheMainSources() {
        Path directory = MainSources.directory();

        assertTrue(directory.isAbsolute(), directory.toString());
        assertTrue(Files.isRegularFile(directory.resolve("monada/neuron/Main.java")), directory.toString());
    }

    @Test
    void aPackageResolvesToItsDirectoryUnderTheMainSources() {
        assertEquals(MainSources.directory().resolve("monada/neuron/routing"),
                MainSources.packageDirectory("monada.neuron.routing"));
    }

    @Test
    void aMissingPropertyFailsWithAMessageNamingTheProperty() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> MainSources.fromProperty(null));

        assertTrue(failure.getMessage().contains(MainSources.PROPERTY), failure.getMessage());
    }

    @Test
    void aBlankPropertyFailsWithAMessageNamingTheProperty() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> MainSources.fromProperty(" "));

        assertTrue(failure.getMessage().contains(MainSources.PROPERTY), failure.getMessage());
    }

    @Test
    void aPropertyThatIsNotADirectoryFailsWithAMessageNamingThePropertyAndThePath(@TempDir Path temp) {
        Path absent = temp.resolve("absent");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> MainSources.fromProperty(absent.toString()));

        assertTrue(failure.getMessage().contains(MainSources.PROPERTY), failure.getMessage());
        assertTrue(failure.getMessage().contains(absent.toString()), failure.getMessage());
    }

    @Test
    void aPropertyThatIsARegularFileFailsWithAMessageNamingTheProperty(@TempDir Path temp) throws IOException {
        Path file = Files.createFile(temp.resolve("Main.java"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> MainSources.fromProperty(file.toString()));

        assertTrue(failure.getMessage().contains(MainSources.PROPERTY), failure.getMessage());
    }

    @Test
    void aRelativeOrUnnormalizedValueComesBackAbsoluteAndNormalized(@TempDir Path temp) throws IOException {
        Path nested = Files.createDirectory(temp.resolve("nested"));

        Path directory = MainSources.fromProperty(nested.resolve("..").resolve("nested").toString());

        assertEquals(nested.toAbsolutePath().normalize(), directory);
        assertTrue(MainSources.fromProperty(".").isAbsolute());
    }

    @Test
    void aNestedPackageResolvesToAnExistingSourceDirectory() {
        Path directory = MainSources.packageDirectory("monada.neuron.routing.adaptation.codec");

        assertTrue(Files.isDirectory(directory), directory.toString());
        assertTrue(directory.startsWith(MainSources.directory()), directory.toString());
    }

    // Issue #165: a test that names the main sources by a working directory path fails outside Gradle's default.
    @Test
    void noTestNamesTheMainSourcesByAWorkingDirectoryPath() throws IOException {
        String needle = "\"src" + "/main";
        Path testSources = MainSources.directory().getParent().getParent().resolve("test").resolve("java");
        List<String> offenders = new ArrayList<>();

        try (var files = Files.walk(testSources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                if (Files.readString(file).contains(needle)) {
                    offenders.add(testSources.relativize(file).toString());
                }
            }
        }

        assertFalse(Files.notExists(testSources), testSources.toString());
        assertEquals(List.of(), offenders, "resolve these through MainSources instead");
    }
}
