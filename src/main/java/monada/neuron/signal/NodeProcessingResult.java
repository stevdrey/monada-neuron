package monada.neuron.signal;

import java.util.List;
import java.util.Objects;

/**
 * Immutable ordered signals emitted by one node-processing operation.
 *
 * <p>The constructor takes an immutable snapshot with {@link List#copyOf(java.util.Collection)}.
 * The source list may therefore be reused or changed by its owner without changing this result.
 * Duplicate signals are preserved because repeated emissions may be meaningful.
 *
 * @param emittedSignals signals in their observable emission order
 */
public record NodeProcessingResult(List<Signal> emittedSignals) {

    private static final NodeProcessingResult NO_OUTPUT = new NodeProcessingResult(List.of());

    /** Validates and snapshots the emitted signal list. */
    public NodeProcessingResult {
        Objects.requireNonNull(emittedSignals, "emittedSignals must not be null");
        emittedSignals = List.copyOf(emittedSignals);
    }

    /**
     * Returns the shared result for a successful operation that emits no signals.
     *
     * @return immutable empty processing result
     */
    public static NodeProcessingResult noOutput() {
        return NO_OUTPUT;
    }
}
