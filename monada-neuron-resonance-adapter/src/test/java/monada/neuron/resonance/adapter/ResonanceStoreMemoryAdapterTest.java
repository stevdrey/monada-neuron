package monada.neuron.resonance.adapter;

import com.monada.api.MonadaMemory;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.memory.ResonanceMemoryCognitiveStage;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.memory.ResonanceMemoryStageResult;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResonanceStoreMemoryAdapterTest {

    @TempDir
    Path directory;

    private MonadaMemory memory;
    private ResonanceStoreMemoryAdapter adapter;

    @BeforeEach
    void open() {
        memory = StoreFixtures.seed(directory);
        adapter = ResonanceStoreMemoryAdapter.using(memory, StoreFixtures.config());
    }

    @Test
    void preservesStoreRankingOrderForOneSignal() {
        var expected = memory.resonate("solar energy").topK(3).execute().results();

        var response = adapter.recall(new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 3));

        assertEquals(expected.size(), response.results().size());
        for (var index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).score(), response.results().get(index).score());
            assertEquals(
                    StoreFixtures.config().signalDecoder().decode(expected.get(index).atom().content()),
                    response.results().get(index).signal());
        }
    }

    @Test
    void mergesSignalsByScoreWithinLimitAndWithoutDuplicates() {
        var response = adapter.recall(
                new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR, StoreFixtures.OCEAN), 4));

        var scores = response.results().stream().map(result -> result.score()).toList();
        var sorted = new ArrayList<>(scores);
        sorted.sort(Comparator.reverseOrder());
        assertTrue(response.results().size() <= 4);
        assertEquals(sorted, scores);
        assertEquals(
                response.results().size(),
                response.results().stream().map(result -> result.reference()).distinct().count());
    }

    @Test
    void duplicateQuerySignalsDoNotDuplicateResults() {
        var single = adapter.recall(new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 3));
        var doubled = adapter.recall(
                new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR, StoreFixtures.SOLAR), 3));

        assertEquals(single.results(), doubled.results());
    }

    @Test
    void referencesAreOpaqueAndDoNotExposeAtomIds() {
        var atomId = memory.remember("solar panels convert sunlight into energy").id();

        var response = adapter.recall(new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 5));

        assertFalse(response.results().isEmpty());
        for (var result : response.results()) {
            assertFalse(result.reference().contains(atomId));
        }
    }

    @Test
    void reusesTheSameOpenStoreAcrossRecalls() {
        var request = new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 10);
        var before = adapter.recall(request).results().size();

        memory.remember("solar flares are bright");

        // The new atom is visible without reopening, so recalls share one open store instance.
        assertEquals(before + 1, adapter.recall(request).results().size());
    }

    @Test
    void closedAdapterIsUnavailable() {
        adapter.close();

        var response = adapter.recall(new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 2));

        assertEquals(ResonanceMemoryStatus.UNAVAILABLE, response.status());
        assertEquals(2, response.resultLimit());
        assertTrue(response.results().isEmpty());
    }

    @Test
    void storeIoFailureBecomesFailedResponse() throws IOException {
        deleteSegments(directory.resolve("vectors"));

        var response = adapter.recall(new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 2));

        assertEquals(ResonanceMemoryStatus.FAILED, response.status());
        assertTrue(response.results().isEmpty());
    }

    @Test
    void decoderRejectionBecomesFailedResponse() {
        var defaults = StoreFixtures.config();
        var config = new ResonanceStoreAdapterConfig(
                defaults.queryEncoder(),
                content -> {
                    throw new IllegalArgumentException("undecodable");
                },
                defaults.memoryOptions(),
                defaults.threshold());
        var failing = ResonanceStoreMemoryAdapter.using(memory, config);

        var response = failing.recall(new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 2));

        assertEquals(ResonanceMemoryStatus.FAILED, response.status());
    }

    @Test
    void openFailureIsTranslatedToAdapterException() throws IOException {
        var invalid = directory.resolve("not-a-directory");
        Files.writeString(invalid, "x");

        assertThrows(
                ResonanceStoreAdapterException.class,
                () -> ResonanceStoreMemoryAdapter.open(invalid, StoreFixtures.config()));
    }

    @Test
    void openCreatesUsableEmptyStore() {
        var fresh = ResonanceStoreMemoryAdapter.open(directory.resolve("fresh"), StoreFixtures.config());

        var response = fresh.recall(new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 2));

        assertEquals(ResonanceMemoryStatus.COMPLETE, response.status());
        assertTrue(response.results().isEmpty());
    }

    @Test
    void cognitiveStageForwardsInputsThenRecalledSignals() {
        var cycle = new DeterministicCognitiveCycle(List.of(new ResonanceMemoryCognitiveStage(adapter, 2)));

        var cycleResult = cycle.execute(
                new PrimaryMonad(new UUID(0L, 1L)),
                List.of(StoreFixtures.SOLAR),
                new CognitiveBudget(10, 10, 20));

        var memoryResult = assertInstanceOf(
                ResonanceMemoryStageResult.class, cycleResult.stageResults().getFirst());
        var recalled = memoryResult.response().results();
        assertEquals(2, recalled.size());
        assertEquals(StoreFixtures.SOLAR, memoryResult.outputSignals().getFirst());
        assertEquals(recalled.getFirst().signal(), memoryResult.outputSignals().get(1));
    }

    private void deleteSegments(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        }
    }
}
