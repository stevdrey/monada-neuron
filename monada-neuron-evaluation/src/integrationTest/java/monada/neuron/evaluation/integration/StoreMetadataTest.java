package monada.neuron.evaluation.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StoreMetadataTest {

    @Test
    void nonRepositoryDirectoryAndMissingPathReportUnknown(@TempDir Path directory) {
        assertEquals("unknown", StoreMetadata.gitCommit(directory));
        assertEquals("unknown", StoreMetadata.gitCommit(directory.resolve("missing")));
        assertEquals("unknown", StoreMetadata.gitCommit(null));
    }

    @Test
    void extractsOnlyReportedManifestFields() {
        var fields = StoreMetadata.manifestFields(
                "{ \"version\": \"0.4\", \"dimensions\": 128, \"vectorSegment\": \"x\", \"encoder\": \"E\" }");

        assertEquals("0.4", fields.get("version"));
        assertEquals("128", fields.get("dimensions"));
        assertEquals("E", fields.get("encoder"));
        assertFalse(fields.containsKey("vectorSegment"));
    }

    @Test
    void reportsDirtyMarkerOnlyForUncommittedChanges(@TempDir Path repo) throws Exception {
        assumeTrue(run(repo, "git", "init", "-q") == 0);
        run(repo, "git", "config", "user.email", "t@example.com");
        run(repo, "git", "config", "user.name", "t");
        Files.writeString(repo.resolve("a.txt"), "one");
        run(repo, "git", "add", "a.txt");
        assumeTrue(run(repo, "git", "commit", "-q", "-m", "init") == 0);

        var clean = StoreMetadata.gitCommit(repo);
        assertFalse(clean.contains("+dirty") || clean.equals("unknown"), clean);

        Files.writeString(repo.resolve("a.txt"), "two");
        assertEquals(clean + "+dirty", StoreMetadata.gitCommit(repo));
    }

    private static int run(Path directory, String... command) throws Exception {
        var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        return process.waitFor();
    }
}
