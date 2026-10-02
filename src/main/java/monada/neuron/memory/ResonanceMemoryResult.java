package monada.neuron.memory;

import monada.neuron.signal.Signal;

import java.util.Objects;

/** One adapter-ordered recalled memory expressed as an opaque reference, Signal, and score. */
public record ResonanceMemoryResult(String reference, Signal signal, double score) {

    /**
     * Maximum reference length shared with {@code MemoryReferenceEvidence}, so recalled references
     * stay compact provenance rather than external payloads. Current adapter references use 35.
     */
    public static final int MAX_REFERENCE_LENGTH = 128;

    /** Validates the adapter-independent result identity and finite ranking score. */
    public ResonanceMemoryResult {
        Objects.requireNonNull(reference, "reference must not be null");
        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
        if (reference.length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(
                    "reference must be at most " + MAX_REFERENCE_LENGTH
                            + " characters, got: " + reference.length());
        }
        Objects.requireNonNull(signal, "signal must not be null");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite, got: " + score);
        }
    }
}
