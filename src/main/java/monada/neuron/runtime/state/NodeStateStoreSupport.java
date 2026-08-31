package monada.neuron.runtime.state;

import monada.neuron.model.FrequencyState;

import java.util.Objects;

/** Package-private validation shared by the equivalent state layouts. */
final class NodeStateStoreSupport {

    static final int CHANNEL_COUNT = 4;
    static final int BYTES_PER_NODE = CHANNEL_COUNT * Double.BYTES;

    private NodeStateStoreSupport() {
    }

    static long logicalBytesFor(int size) {
        requirePositiveSize(size);
        try {
            return Math.multiplyExact((long) size, BYTES_PER_NODE);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("node state layout exceeds supported byte size", exception);
        }
    }

    static void requirePositiveSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive, got: " + size);
        }
    }

    static void requireIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + " out of bounds for size " + size);
        }
    }

    static void requireOpen(boolean open) {
        if (!open) {
            throw new IllegalStateException("node state store is closed");
        }
    }

    static void validateState(FrequencyState state, double energy) {
        Objects.requireNonNull(state, "state must not be null");
        if (!Double.isFinite(energy)) {
            throw new IllegalArgumentException("energy must be finite, got: " + energy);
        }
        if (energy < 0.0) {
            throw new IllegalArgumentException("energy must be non-negative, got: " + energy);
        }
    }
}
