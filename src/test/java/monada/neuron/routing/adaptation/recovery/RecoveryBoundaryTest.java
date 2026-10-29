package monada.neuron.routing.adaptation.recovery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recovery package is values and pure functions: no IO, logging, threads, store mutation or reset. */
class RecoveryBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java/monada/neuron/routing/adaptation/recovery");

    /** Names that would read or write a file, socket or log, run work elsewhere, or reset a store. */
    private static final List<String> BANNED = List.of("java.nio.file", "java.io.File", "java.net", "java.nio.channels",
            "java.util.logging", "System.Logger", "System.getLogger", "System.out", "System.err", "printStackTrace",
            "ObjectInputStream", "ObjectOutputStream", "Serializable", "Externalizable", "java.util.concurrent",
            "Thread", "Executor", "resetCopy", "DISCARD", "RESET", "COLD_START");

    /** Store calls that change state; the package example in {@code package-info.java} shows the host calling one. */
    private static final List<String> MUTATIONS = List.of(".bind(", ".compact(", ".rebuild(", ".rebuildFrom(",
            ".consume(", ".advanceCutoff(", ".unbindDormant(");

    private static List<Path> sources() throws IOException {
        try (var listing = Files.list(SOURCES)) {
            return listing.sorted().toList();
        }
    }

    @Test
    void thePackageHoldsTheTenPublicTypesAndNothingElse() throws IOException {
        assertEquals(List.of("BlockerKind.java", "Caveat.java", "CheckpointRecovery.java", "ExportState.java",
                "RecoveryAction.java", "RecoveryAssessment.java", "RecoveryBlocker.java", "RecoveryPlan.java",
                "RecoveryStep.java", "ReplayWindow.java", "package-info.java"),
                sources().stream().map(path -> path.getFileName().toString()).toList());
        assertTrue(Modifier.isFinal(CheckpointRecovery.class.getModifiers()));
        assertTrue(Arrays.stream(CheckpointRecovery.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    // covers: AC-5
    @Test
    void noSourceReachesIoLoggingThreadsOrAStoreMutation() throws IOException {
        for (Path source : sources()) {
            String text = Files.readString(source);
            for (String banned : BANNED) {
                assertFalse(text.contains(banned), source.getFileName() + " mentions " + banned);
            }
            if (!source.getFileName().toString().equals("package-info.java")) {
                for (String mutation : MUTATIONS) {
                    assertFalse(text.contains(mutation), source.getFileName() + " calls " + mutation);
                }
            }
        }
    }

    // covers: AC-5
    @Test
    void theValueTypesAreNotSerializableAndHoldNoStore() {
        for (Class<?> type : List.of(RecoveryAssessment.Compatible.class, RecoveryAssessment.Blocked.class,
                RecoveryBlocker.Undecodable.class, RecoveryBlocker.FromStore.class, RecoveryStep.class,
                RecoveryPlan.class, ReplayWindow.class)) {
            assertFalse(Serializable.class.isAssignableFrom(type), type.getSimpleName());
            assertTrue(Arrays.stream(type.getRecordComponents())
                    .noneMatch(component -> component.getType().getSimpleName().equals("RoutingStateStore")),
                    type.getSimpleName() + " holds a store");
        }
    }
}
