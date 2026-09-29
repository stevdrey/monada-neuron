package monada.neuron.evaluation.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
