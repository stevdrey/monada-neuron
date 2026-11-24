// ABOUTME: Locates the main Java source directory that the build passes to tests as a system property.
// ABOUTME: Boundary tests read sources through it, so they never depend on the working directory.
package monada.neuron.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/** The main source directory, taken from the {@value #PROPERTY} system property that {@code tasks.test} sets. */
public final class MainSources {

    /** Set in {@code build.gradle.kts} from the same directory the {@code mainSources} test input declares. */
    public static final String PROPERTY = "monada.mainSources";

    private MainSources() {
    }

    /** The absolute main source directory; fails with a message naming {@value #PROPERTY} when it is not usable. */
    public static Path directory() {
        return fromProperty(System.getProperty(PROPERTY));
    }

    /** The directory of {@code packageName} (dot separated) under the main source directory. */
    public static Path packageDirectory(String packageName) {
        return directory().resolve(packageName.replace('.', '/'));
    }

    static Path fromProperty(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("System property '" + PROPERTY + "' is not set. Run the tests through"
                    + " Gradle, or pass -D" + PROPERTY + "=<absolute path to src/main/java>.");
        }
        Path directory = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("System property '" + PROPERTY + "' points to '" + directory
                    + "', which is not a directory.");
        }
        return directory;
    }
}
