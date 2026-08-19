package monada.neuron.memory;

import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/** Immutable batch recall request expressed only in Neuron signal semantics. */
public record ResonanceMemoryRequest(List<Signal> querySignals, int maxResults) {

    /** Requires at least one query Signal and an explicit positive result limit. */
    public ResonanceMemoryRequest {
        querySignals = List.copyOf(Objects.requireNonNull(querySignals, "querySignals must not be null"));
        if (querySignals.isEmpty()) {
            throw new IllegalArgumentException("querySignals must not be empty");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be positive, got: " + maxResults);
        }
    }
}
