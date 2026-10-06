package monada.neuron.resonance.adapter;

import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the adapter's Neuron-owned merge over a store-free {@link ResonanceStoreRecall}. */
final class StubStoreRecallAdapterTest {

    private static ResonanceResult result(String id, double score) {
        return new ResonanceResult(
                new KnowledgeAtom(id, AtomType.TEXT, "content-" + id, Map.of(), 1.0, Instant.EPOCH),
                score);
    }

    private static ResonanceStoreMemoryAdapter adapter(ResonanceStoreRecall recall) {
        return ResonanceStoreMemoryAdapter.using(recall, ResonanceStoreAdapterConfig.defaults());
    }

    private static String reference(String id) {
        return new OpaqueReference().of(id);
    }

    @Test
    void mergesByScoreThenSignalOrderThenRankAndDeduplicates() {
        var first = StoreFixtures.signal(1.0);
        var second = StoreFixtures.signal(2.0);
        var encoder = ResonanceStoreAdapterConfig.defaults().queryEncoder();
        var perQuery = Map.of(
                encoder.encode(first), List.of(result("a", 0.9), result("b", 0.5)),
                encoder.encode(second), List.of(result("c", 0.9), result("a", 0.95), result("b", 0.5)));

        var response = adapter((query, limit, threshold) -> perQuery.get(query))
                .recall(new ResonanceMemoryRequest(List.of(first, second), 10));

        assertEquals(ResonanceMemoryStatus.COMPLETE, response.status());
        // a wins with 0.95 (second signal); c ties a's first score but is below it; b keeps signal 0.
        assertEquals(
                List.of(reference("a"), reference("c"), reference("b")),
                response.results().stream().map(ResonanceMemoryResult::reference).toList());
        assertEquals(List.of(0.95, 0.9, 0.5), response.results().stream().map(ResonanceMemoryResult::score).toList());
    }

    @Test
    void cutsToMaxResults() {
        var response = adapter((query, limit, threshold) ->
                List.of(result("a", 0.9), result("b", 0.8), result("c", 0.7)))
                .recall(new ResonanceMemoryRequest(List.of(StoreFixtures.signal(1.0)), 2));

        assertEquals(2, response.results().size());
        assertEquals(reference("a"), response.results().getFirst().reference());
    }

    @Test
    void forwardsLimitAndThresholdToTheStore() {
        var seen = new int[1];
        var config = ResonanceStoreAdapterConfig.defaults();
        ResonanceStoreMemoryAdapter.using((query, limit, threshold) -> {
            seen[0] = limit;
            assertEquals(config.threshold(), threshold);
            return List.of();
        }, config).recall(new ResonanceMemoryRequest(List.of(StoreFixtures.signal(1.0)), 7));

        assertEquals(7, seen[0]);
    }

    @Test
    void nonFiniteScoreBecomesFailedResponse() {
        var response = adapter((query, limit, threshold) -> List.of(result("a", Double.NaN)))
                .recall(new ResonanceMemoryRequest(List.of(StoreFixtures.signal(1.0)), 3));

        assertEquals(ResonanceMemoryStatus.FAILED, response.status());
        assertTrue(response.results().isEmpty());
    }
}
