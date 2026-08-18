package monada.neuron.context;

import java.util.Objects;

/** One sequenced deterministic trace event. */
public record CognitiveTraceEntry(long sequence, CognitiveTraceEvent event) {

    /** Validates trace ordering metadata and the event payload. */
    public CognitiveTraceEntry {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative, got: " + sequence);
        }
        Objects.requireNonNull(event, "event must not be null");
    }
}
