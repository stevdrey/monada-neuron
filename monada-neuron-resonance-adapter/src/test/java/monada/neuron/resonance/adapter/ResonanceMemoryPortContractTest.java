package monada.neuron.resonance.adapter;

import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable port semantics every {@link ResonanceMemoryPort} implementation must satisfy. */
abstract class ResonanceMemoryPortContractTest {

    /** Returns a port whose {@link StoreFixtures#SOLAR} query has at least three matches. */
    abstract ResonanceMemoryPort port();

    @Test
    void echoesRequestLimitAndNeverExceedsIt() {
        var response = port().recall(new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 2));

        assertEquals(ResonanceMemoryStatus.COMPLETE, response.status());
        assertEquals(2, response.resultLimit());
        assertEquals(2, response.results().size());
    }

    @Test
    void noMatchIsCompleteAndEmpty() {
        var response = port().recall(new ResonanceMemoryRequest(List.of(StoreFixtures.BLANK), 3));

        assertEquals(ResonanceMemoryStatus.COMPLETE, response.status());
        assertEquals(3, response.resultLimit());
        assertTrue(response.results().isEmpty());
    }

    @Test
    void repeatedRecallIsDeterministic() {
        var request = new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR, StoreFixtures.OCEAN), 4);

        assertEquals(port().recall(request), port().recall(request));
    }

    @Test
    void resultsCarryOpaqueReferencesAndFiniteScores() {
        var response = port().recall(new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 3));

        for (var result : response.results()) {
            assertTrue(!result.reference().isBlank());
            assertTrue(Double.isFinite(result.score()));
        }
    }

    @Test
    void failureStatusesCarryNoResults() {
        var response = port().recall(new ResonanceMemoryRequest(List.of(StoreFixtures.SOLAR), 1));

        if (response.status() != ResonanceMemoryStatus.COMPLETE
                && response.status() != ResonanceMemoryStatus.PARTIAL) {
            assertTrue(response.results().isEmpty());
        }
    }
}
