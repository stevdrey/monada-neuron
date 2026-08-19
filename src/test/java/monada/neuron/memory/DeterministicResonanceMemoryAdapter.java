package monada.neuron.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic test-only memory adapter backed by exact request/response fixtures. */
final class DeterministicResonanceMemoryAdapter implements ResonanceMemoryPort {

    private final Map<ResonanceMemoryRequest, ResonanceMemoryResponse> responses;
    private final List<ResonanceMemoryRequest> receivedRequests = new ArrayList<>();

    /** Snapshots fixtures and requires every response limit to match its exact request. */
    DeterministicResonanceMemoryAdapter(
            Map<ResonanceMemoryRequest, ResonanceMemoryResponse> responses) {
        Objects.requireNonNull(responses, "responses must not be null");
        var stableResponses = new LinkedHashMap<ResonanceMemoryRequest, ResonanceMemoryResponse>();
        for (var entry : responses.entrySet()) {
            var request = Objects.requireNonNull(entry.getKey(), "fixture request must not be null");
            var response = Objects.requireNonNull(entry.getValue(), "fixture response must not be null");
            if (response.resultLimit() != request.maxResults()) {
                throw new IllegalArgumentException("fixture response limit must match its request");
            }
            stableResponses.put(request, response);
        }
        this.responses = Map.copyOf(stableResponses);
    }

    /** Returns the exact fixture or a deterministic complete response with no matches. */
    @Override
    public ResonanceMemoryResponse recall(ResonanceMemoryRequest request) {
        var stableRequest = Objects.requireNonNull(request, "request must not be null");
        receivedRequests.add(stableRequest);
        return responses.getOrDefault(
                stableRequest,
                new ResonanceMemoryResponse(
                        ResonanceMemoryStatus.COMPLETE,
                        stableRequest.maxResults(),
                        List.of()));
    }

    /** Returns requests in recall order as an immutable test observation. */
    List<ResonanceMemoryRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }
}
