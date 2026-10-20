package monada.neuron.evaluation.knowledge;

import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryRequest;
import monada.neuron.memory.ResonanceMemoryResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link ResonanceMemoryPort} decorator that records every request and the delegate's full response at the port
 * boundary (protocol {@code l3-evidence-v1}, "Recalled"). The memory stage result alone cannot give the recalled count:
 * when the cycle admits only part of the output, it trims the response to the admitted prefix.
 *
 * <p>Single-threaded, like the deterministic cycle that calls it; one instance serves one cycle.
 */
final class RecordingMemoryPort implements ResonanceMemoryPort {

    /** One recorded port call. */
    record Call(ResonanceMemoryRequest request, ResonanceMemoryResponse response) {

        Call {
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(response, "response must not be null");
        }
    }

    private final ResonanceMemoryPort delegate;
    private final List<Call> calls = new ArrayList<>(1);

    RecordingMemoryPort(ResonanceMemoryPort delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public ResonanceMemoryResponse recall(ResonanceMemoryRequest request) {
        var response = delegate.recall(request);
        if (response != null) {
            calls.add(new Call(request, response));
        }
        return response;
    }

    /** Returns the recorded calls in call order. */
    List<Call> calls() {
        return List.copyOf(calls);
    }
}
