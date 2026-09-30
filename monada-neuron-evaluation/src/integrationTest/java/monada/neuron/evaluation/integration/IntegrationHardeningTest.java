package monada.neuron.evaluation.integration;

import monada.neuron.evaluation.integration.ResonanceStoreIntegrationEvaluation.Check;
import monada.neuron.evaluation.metrics.EnvironmentMetadata;
import monada.neuron.evaluation.metrics.EvaluationReport;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.resonance.adapter.ResonanceStoreMemoryAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IntegrationHardeningTest {

    private final FixtureSignalCodec codec = new FixtureSignalCodec();

    @Test
    void releasingClearsTheHolderSlotAndTheStoreRetention() {
        try (var store = TemporaryResonanceStore.seeded(codec)) {
            var holder = new ResonanceStoreMemoryAdapter[] {store.openAdapter()};

            ResonanceStoreIntegrationEvaluation.releaseAdapter(store, holder);

            assertNull(holder[0]);
            assertEquals(0, store.retainedAdapterCount());
            assertDoesNotThrow(() -> ResonanceStoreIntegrationEvaluation.releaseAdapter(store, holder));
        }
    }

    @Test
    void closingAStoreHolderClearsTheSlot() {
        var holder = new TemporaryResonanceStore[] {TemporaryResonanceStore.seeded(codec)};
        var store = holder[0];

        ResonanceStoreIntegrationEvaluation.closeStore(holder);

        assertNull(holder[0]);
        assertFalse(store.exists());
    }

    @Test
    void reportMetadataIsImmutableAndDetachedFromTheSource() {
        var source = new HashMap<String, String>(Map.of("b", "2", "a", "1"));
        var report = new ResonanceStoreIntegrationReport(
                new EvaluationReport(Instant.EPOCH, EnvironmentMetadata.current(), List.of()),
                List.<Check>of(),
                source);
        var before = report.toJson();

        source.put("c", "3");

        assertThrows(UnsupportedOperationException.class, () -> report.metadata().put("d", "4"));
        assertEquals(before, report.toJson());
        assertEquals(List.of("a", "b"), List.copyOf(report.metadata().keySet()));
    }

    @Test
    void failedCleanupCanBeRetried() throws IOException {
        var store = TemporaryResonanceStore.seeded(codec);
        var nested = store.path().resolve("vectors");
        var posix = Files.getFileStore(nested).supportsFileAttributeView("posix");
        var root = "root".equals(System.getProperty("user.name"));
        if (!posix || root) {
            store.close();
        }
        assumeTrue(posix && !root);
        Files.setPosixFilePermissions(nested, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThrows(UncheckedIOException.class, store::close);
            assertTrue(store.exists());
        } finally {
            Files.setPosixFilePermissions(nested, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        store.close();

        assertFalse(store.exists());
    }

    @Test
    void measuredIterationsRejectFailedOrDivergentResponses() {
        try (var store = TemporaryResonanceStore.seeded(codec)) {
            var signal = ResonanceStoreFixtureCorpus.query("solar-energy").signal();
            var sample = store.openAdapter().recall(new ResonanceMemoryRequest(List.of(signal), 3));
            var failed = new ResonanceMemoryResponse(ResonanceMemoryStatus.FAILED, 3, List.of());
            var divergent = new ResonanceMemoryResponse(
                    ResonanceMemoryStatus.COMPLETE, 3, sample.results().subList(0, 1));

            assertDoesNotThrow(() -> ResonanceStoreIntegrationEvaluation.requireSameResponse(sample, sample));
            assertThrows(IllegalStateException.class,
                    () -> ResonanceStoreIntegrationEvaluation.requireSameResponse(failed, sample));
            assertThrows(IllegalStateException.class,
                    () -> ResonanceStoreIntegrationEvaluation.requireSameResponse(divergent, sample));
        }
    }

    @Test
    void failedCloseKeepsTheHolderSoCleanupCanRetry() throws IOException {
        var store = TemporaryResonanceStore.seeded(codec);
        var holder = new TemporaryResonanceStore[] {store};
        var nested = store.path().resolve("vectors");
        var posix = Files.getFileStore(nested).supportsFileAttributeView("posix");
        var root = "root".equals(System.getProperty("user.name"));
        if (!posix || root) {
            store.close();
        }
        assumeTrue(posix && !root);
        Files.setPosixFilePermissions(nested, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThrows(UncheckedIOException.class, () -> ResonanceStoreIntegrationEvaluation.closeStore(holder));
            assertEquals(store, holder[0]);
        } finally {
            Files.setPosixFilePermissions(nested, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        ResonanceStoreIntegrationEvaluation.closeStore(holder);

        assertNull(holder[0]);
        assertFalse(store.exists());
    }

    @Test
    void cycleValidationRejectsADegradedMemoryResponse() {
        var evaluation = new ResonanceStoreIntegrationEvaluation(42L, true);
        try (var store = TemporaryResonanceStore.seeded(codec)) {
            var adapter = store.openAdapter();
            var inputs = List.of(ResonanceStoreFixtureCorpus.query("solar-energy").signal());
            var good = evaluation.executeForTest(adapter, inputs);
            adapter.close();
            var degraded = evaluation.executeForTest(adapter, inputs);

            assertDoesNotThrow(() -> ResonanceStoreIntegrationEvaluation.requireSameCycle(good, good));
            assertThrows(IllegalStateException.class,
                    () -> ResonanceStoreIntegrationEvaluation.requireSameCycle(degraded, good));
        }
    }
}
