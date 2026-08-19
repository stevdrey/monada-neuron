package monada.neuron.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic test-only action capability backed by exact request/result fixtures. */
final class DeterministicActionExecutor implements ActionCapability {

    private final Map<ActionRequest, ActionResult> results;
    private final List<ActionRequest> receivedRequests = new ArrayList<>();

    /** Snapshots fixtures and requires every result limit to match its exact request. */
    DeterministicActionExecutor(Map<ActionRequest, ActionResult> results) {
        Objects.requireNonNull(results, "results must not be null");
        var stableResults = new LinkedHashMap<ActionRequest, ActionResult>();
        for (var entry : results.entrySet()) {
            var request = Objects.requireNonNull(entry.getKey(), "fixture request must not be null");
            var result = Objects.requireNonNull(entry.getValue(), "fixture result must not be null");
            if (result.observationLimit() != request.maxObservations()) {
                throw new IllegalArgumentException("fixture result limit must match its request");
            }
            stableResults.put(request, result);
        }
        this.results = Map.copyOf(stableResults);
    }

    /** Returns the exact fixture or a deterministic unavailable result for an unknown request. */
    @Override
    public ActionResult execute(ActionRequest request) {
        var stableRequest = Objects.requireNonNull(request, "request must not be null");
        receivedRequests.add(stableRequest);
        return results.getOrDefault(
                stableRequest,
                new ActionResult(ActionStatus.UNAVAILABLE, stableRequest.maxObservations(), List.of()));
    }

    /** Returns requests in execution order as an immutable test observation. */
    List<ActionRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }
}
