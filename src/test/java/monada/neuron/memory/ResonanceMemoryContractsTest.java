package monada.neuron.memory;

import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResonanceMemoryContractsTest {

    @Test
    void requestAndResponseSnapshotOrderedCollectionsAndPreserveDuplicateTies() {
        var queries = new ArrayList<>(List.of(signal(1.0)));
        var results = new ArrayList<>(List.of(
                result("memory-a", 2.0, 0.7),
                result("memory-b", 2.0, 0.7),
                result("memory-b", 2.0, 0.7)));
        var request = new ResonanceMemoryRequest(queries, 3);
        var response = new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE, 3, results);
        queries.clear();
        results.clear();

        assertAll(
                () -> assertEquals(List.of(signal(1.0)), request.querySignals()),
                () -> assertEquals(List.of(
                        result("memory-a", 2.0, 0.7),
                        result("memory-b", 2.0, 0.7),
                        result("memory-b", 2.0, 0.7)), response.results()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> request.querySignals().clear()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> response.results().clear()));
    }

    @Test
    void rejectsInvalidRequestsResultsAndResponses() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new ResonanceMemoryRequest(null, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ResonanceMemoryRequest(List.of(), 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ResonanceMemoryRequest(List.of(signal(1.0)), 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ResonanceMemoryResult(" ", signal(1.0), 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ResonanceMemoryResult("memory", signal(1.0), Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ResonanceMemoryResponse(
                                ResonanceMemoryStatus.COMPLETE,
                                1,
                                List.of(result("one", 1.0, 0.1), result("two", 2.0, 0.2)))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ResonanceMemoryResponse(
                                ResonanceMemoryStatus.TIMED_OUT,
                                1,
                                List.of(result("one", 1.0, 0.1)))));
    }

    @Test
    void exposesNoResultPartialAndExpectedFailureSemantics() {
        var noResult = new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE, 2, List.of());
        var partial = new ResonanceMemoryResponse(
                ResonanceMemoryStatus.PARTIAL,
                2,
                List.of(result("partial", 1.0, -0.3)));

        assertAll(
                () -> assertEquals(ResonanceMemoryStatus.COMPLETE, noResult.status()),
                () -> assertEquals(List.of(), noResult.results()),
                () -> assertEquals(ResonanceMemoryStatus.PARTIAL, partial.status()),
                () -> assertEquals(-0.3, partial.results().getFirst().score()),
                () -> assertEquals(List.of(), new ResonanceMemoryResponse(
                        ResonanceMemoryStatus.UNAVAILABLE, 2, List.of()).results()),
                () -> assertEquals(List.of(), new ResonanceMemoryResponse(
                        ResonanceMemoryStatus.FAILED, 2, List.of()).results()));
    }

    @Test
    void deterministicAdapterReturnsExactFixturesDefaultsToNoMatchesAndRecordsRequests() {
        var fixtureRequest = new ResonanceMemoryRequest(List.of(signal(1.0)), 2);
        var fixtureResponse = new ResonanceMemoryResponse(
                ResonanceMemoryStatus.COMPLETE,
                2,
                List.of(result("match", 2.0, 0.9)));
        var adapter = new DeterministicResonanceMemoryAdapter(Map.of(fixtureRequest, fixtureResponse));
        var defaultRequest = new ResonanceMemoryRequest(List.of(signal(3.0)), 2);

        assertAll(
                () -> assertEquals(fixtureResponse, adapter.recall(fixtureRequest)),
                () -> assertEquals(new ResonanceMemoryResponse(
                        ResonanceMemoryStatus.COMPLETE, 2, List.of()), adapter.recall(defaultRequest)),
                () -> assertEquals(List.of(fixtureRequest, defaultRequest), adapter.receivedRequests()));
    }

    private ResonanceMemoryResult result(String reference, double amplitude, double score) {
        return new ResonanceMemoryResult(reference, signal(amplitude), score);
    }

    private Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }
}
