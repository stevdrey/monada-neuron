package monada.neuron.evaluation.integration;

import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryResonanceStoreTest {

    private final FixtureSignalCodec codec = new FixtureSignalCodec();

    @Test
    void seedsFixtureCorpusIntoIsolatedDirectoryAndDeletesItOnClose() {
        var store = TemporaryResonanceStore.seeded(codec);
        var path = store.path();
        assertTrue(store.exists());
        assertTrue(store.seedNanos() > 0);

        var query = ResonanceStoreFixtureCorpus.query("ocean-tide");
        var response = store.openAdapter().recall(new ResonanceMemoryRequest(List.of(query.signal()), 2));
        assertEquals(ResonanceMemoryStatus.COMPLETE, response.status());
        assertEquals("ocean-tide", codec.labelOf(response.results().getFirst().signal()).orElseThrow());

        store.close();

        assertFalse(store.exists());
        assertFalse(Files.exists(path));
    }

    @Test
    void twoStoresDoNotShareState() {
        try (var first = TemporaryResonanceStore.seeded(codec);
                var second = TemporaryResonanceStore.empty(codec)) {
            assertFalse(first.path().equals(second.path()));
        }
    }

    @Test
    void closingTwiceIsHarmless() {
        var store = TemporaryResonanceStore.seeded(codec);
        store.close();
        store.close();

        assertFalse(store.exists());
    }

    @Test
    void releasedAdaptersAreClosedAndNoLongerRetained() {
        try (var store = TemporaryResonanceStore.seeded(codec)) {
            var adapter = store.openAdapter();
            assertEquals(1, store.retainedAdapterCount());

            store.release(adapter);

            assertEquals(0, store.retainedAdapterCount());
            var response = adapter.recall(new ResonanceMemoryRequest(
                    List.of(ResonanceStoreFixtureCorpus.query("ocean-tide").signal()), 1));
            assertEquals(ResonanceMemoryStatus.UNAVAILABLE, response.status());
        }
    }
}
