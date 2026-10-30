package monada.neuron.routing.adaptation.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The codec package works on in-memory arrays only and keeps no channel for checkpoint bytes or host tokens. */
class CheckpointBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java/monada/neuron/routing/adaptation/codec");

    /** Names that would read or write a file, socket or log, or use Java object serialization. */
    private static final List<String> BANNED = List.of("java.nio.file", "java.io.File", "java.net", "java.nio.channels",
            "java.util.logging", "System.Logger", "System.getLogger", "System.out", "System.err", "printStackTrace",
            "ObjectInputStream", "ObjectOutputStream", "Serializable", "Externalizable", "readObject",
            "writeObject");

    private static List<Path> sources() throws IOException {
        try (var listing = Files.list(SOURCES)) {
            return listing.sorted().toList();
        }
    }

    @Test
    void thePackageHoldsThreePublicTypesAndAPrivateWriterAndReader() throws IOException {
        assertEquals(List.of("CheckpointCodec.java", "CheckpointDecodeResult.java", "CheckpointRejectReason.java",
                "Reader.java", "Writer.java", "package-info.java"),
                sources().stream().map(path -> path.getFileName().toString()).toList());
        assertFalse(Modifier.isPublic(Writer.class.getModifiers()));
        assertFalse(Modifier.isPublic(Reader.class.getModifiers()));
        assertTrue(Modifier.isFinal(CheckpointCodec.class.getModifiers()));
        assertTrue(Arrays.stream(CheckpointCodec.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    @Test
    void noSourceReachesAFileNetworkLoggingOrSerializationApi() throws IOException {
        for (Path source : sources()) {
            String text = Files.readString(source);
            for (String banned : BANNED) {
                assertFalse(text.contains(banned), source.getFileName() + " mentions " + banned);
            }
        }
    }

    @Test
    void theResultRecordsAreNotSerializable() {
        assertFalse(Serializable.class.isAssignableFrom(CheckpointDecodeResult.Decoded.class));
        assertFalse(Serializable.class.isAssignableFrom(CheckpointDecodeResult.Rejected.class));
    }

    @Test
    void aRejectionCarriesOnlyItsReason() {
        assertEquals(List.of("reason"), Arrays.stream(CheckpointDecodeResult.Rejected.class.getRecordComponents())
                .map(RecordComponent::getName).toList());
        assertEquals("Rejected[reason=CORRUPT]",
                new CheckpointDecodeResult.Rejected(CheckpointRejectReason.CORRUPT).toString());
    }
}
