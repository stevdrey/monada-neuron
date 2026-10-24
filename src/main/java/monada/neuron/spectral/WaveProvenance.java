package monada.neuron.spectral;

import monada.neuron.memory.ResonanceMemoryResult;

import java.util.Objects;

/**
 * Host-declared origin of the samples a {@link SpectralPattern} was decomposed from.
 *
 * <p>All values are opaque to Neuron: it never infers meaning from them. Both strings are compact references bounded
 * like {@link ResonanceMemoryResult#MAX_REFERENCE_LENGTH} (UTF-16 chars), never payloads, text or file content.
 *
 * @param encoderId opaque identity of the host encoder that produced the samples
 * @param encoderVersion encoder version, at least {@code 1}
 * @param sourceReference opaque reference to the host source of the window
 * @param windowOffset index of the window's first sample in the host stream, at least {@code 0}
 */
public record WaveProvenance(String encoderId, int encoderVersion, String sourceReference, long windowOffset) {

    /** Validates the provenance bounds of contract section 4.1. */
    public WaveProvenance {
        requireReference(encoderId, "encoderId");
        if (encoderVersion < 1) {
            throw new IllegalArgumentException("encoderVersion must be at least 1, got: " + encoderVersion);
        }
        requireReference(sourceReference, "sourceReference");
        if (windowOffset < 0) {
            throw new IllegalArgumentException("windowOffset must be non-negative, got: " + windowOffset);
        }
    }

    /**
     * Requires that a window of {@code sampleCount} samples starting at {@link #windowOffset()} stays within a
     * {@code long} index.
     *
     * @param sampleCount number of samples in the window
     * @throws IllegalArgumentException if {@code windowOffset + sampleCount} overflows
     */
    void requireWindowFits(int sampleCount) {
        if (windowOffset > Long.MAX_VALUE - sampleCount) {
            throw new IllegalArgumentException("windowOffset + sampleCount overflows: windowOffset=" + windowOffset
                    + ", sampleCount=" + sampleCount);
        }
    }

    private static void requireReference(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > ResonanceMemoryResult.MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(name + " must be at most " + ResonanceMemoryResult.MAX_REFERENCE_LENGTH
                    + " characters, got: " + value.length());
        }
    }
}
