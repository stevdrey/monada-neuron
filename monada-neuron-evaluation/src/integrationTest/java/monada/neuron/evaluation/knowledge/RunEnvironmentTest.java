package monada.neuron.evaluation.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEnvironmentTest {

    @TempDir
    Path directory;

    @Test
    void cpuModelIsTheFirstModelNameOrUnknown() throws IOException {
        var cpuInfo = Files.writeString(directory.resolve("cpuinfo"),
                "processor\t: 0\nmodel name\t: Example CPU 9000\nprocessor\t: 1\nmodel name\t: Other\n");

        assertEquals("Example CPU 9000", new RunEnvironment(cpuInfo, directory).cpuModel());
        assertTrue(new RunEnvironment(directory.resolve("missing"), directory).cpuModel().startsWith("unknown ("));
    }

    @Test
    void storageTypeUsesTheRotationalFlagWithAPartitionFallback() throws IOException {
        var blocks = Files.createDirectories(directory.resolve("block"));
        Files.createDirectories(blocks.resolve("sda/queue"));
        Files.writeString(blocks.resolve("sda/queue/rotational"), "1\n");
        Files.createDirectories(blocks.resolve("nvme0n1/queue"));
        Files.writeString(blocks.resolve("nvme0n1/queue/rotational"), "0\n");
        Files.createDirectories(blocks.resolve("nvme0n1/nvme0n1p2"));
        Files.createSymbolicLink(blocks.resolve("nvme0n1p2"), blocks.resolve("nvme0n1/nvme0n1p2"));
        var environment = new RunEnvironment(directory.resolve("cpuinfo"), blocks);

        assertEquals("hdd (rotational)", environment.storageType("ext4", "/dev/sda"));
        assertEquals("ssd (non-rotational)", environment.storageType("btrfs", "/dev/nvme0n1p2"));
        assertEquals("memory-backed (tmpfs)", environment.storageType("tmpfs", "tmpfs"));
        assertTrue(environment.storageType("ext4", "/dev/unknown0").startsWith("unknown ("));
        assertTrue(environment.storageType("nfs", "server:/export").startsWith("unknown ("));
    }

    @Test
    void hostIdIsSaltedTruncatedAndNeverThePlainName() {
        var id = RunEnvironment.hostId("build-host.example");

        assertEquals(id, RunEnvironment.hostId("build-host.example"));
        assertTrue(id.matches("[0-9a-f]{16}"), id);
        assertFalse(id.equals(CorpusDigests.sha256("build-host.example").substring(0, 16)), "salted");
    }

    @Test
    void describedFieldsAreAlwaysPresentEvenForAMissingRoot() {
        var metadata = new TreeMap<String, String>();
        var environment = new RunEnvironment();
        environment.describeHost(metadata);
        environment.describeStoreRoot("env.storeRoot", directory.resolve("not/yet/created"), metadata);

        for (var key : List.of("env.cpuModel", "env.totalPhysicalMemoryBytes", "env.hostId",
                "env.storeRoot.fileSystem", "env.storeRoot.storageType")) {
            assertTrue(metadata.containsKey(key) && !metadata.get(key).isBlank(), key);
        }
        assertFalse(metadata.get("env.storeRoot.fileSystem").startsWith("unknown"),
                "the nearest existing ancestor is described");
    }
}
