package monada.neuron.memory;

import monada.neuron.signal.Signal;

import java.util.Objects;

/** One adapter-ordered recalled memory expressed as an opaque reference, Signal, and score. */
public record ResonanceMemoryResult(String reference, Signal signal, double score) {

    /** Validates the adapter-independent result identity and finite ranking score. */
    public ResonanceMemoryResult {
        Objects.requireNonNull(reference, "reference must not be null");
        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
        Objects.requireNonNull(signal, "signal must not be null");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite, got: " + score);
        }
    }
}
