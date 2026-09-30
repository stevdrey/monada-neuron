package monada.neuron.evaluation.integration;

import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * No-I/O port that replays responses recorded from a real port.
 *
 * <p>Used as the cycle-cost reference so downstream stages process exactly the Signals the real store
 * produced; the latency difference to the real run then isolates memory-port cost.
 */
final class ReplayMemoryPort implements ResonanceMemoryPort {

    private final Map<ResonanceMemoryRequest, ResonanceMemoryResponse> responses;

    private ReplayMemoryPort(Map<ResonanceMemoryRequest, ResonanceMemoryResponse> responses) {
        this.responses = Map.copyOf(responses);
    }

    @Override
    public ResonanceMemoryResponse recall(ResonanceMemoryRequest request) {
        var response = responses.get(Objects.requireNonNull(request, "request must not be null"));
        if (response == null) {
            throw new IllegalStateException("no recorded response for request: " + request);
        }
        return response;
    }

    /** Wraps a real port and records every request/response pair it serves. */
    static final class Recorder implements ResonanceMemoryPort {

        private final ResonanceMemoryPort delegate;
        private final Map<ResonanceMemoryRequest, ResonanceMemoryResponse> recorded = new LinkedHashMap<>();

        Recorder(ResonanceMemoryPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        }

        @Override
        public ResonanceMemoryResponse recall(ResonanceMemoryRequest request) {
            var response = delegate.recall(request);
            recorded.put(request, response);
            return response;
        }

        /** Returns a port replaying everything recorded so far. */
        ReplayMemoryPort replay() {
            return new ReplayMemoryPort(recorded);
        }
    }
}
