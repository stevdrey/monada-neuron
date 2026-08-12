package monada.neuron.runtime.graph;

import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Immutable observable result of one propagation.
 *
 * <p>The emitted list contains every signal emitted by every completed processing step in global
 * execution order. Limit flags report independent truncation causes and may both be {@code true}.
 *
 * @param emittedSignals all emitted signals in deterministic execution order
 * @param processedSteps number of completed node-processing invocations
 * @param stepLimitReached whether queued work remained after the maximum step count
 * @param hopLimitReached whether an accepted route was suppressed by the maximum hop count
 */
public record PropagationResult(
        List<Signal> emittedSignals,
        int processedSteps,
        boolean stepLimitReached,
        boolean hopLimitReached) {

    /** Validates scalar state and snapshots the emitted-signal list. */
    public PropagationResult {
        Objects.requireNonNull(emittedSignals, "emittedSignals must not be null");
        emittedSignals = List.copyOf(emittedSignals);
        if (processedSteps < 0) {
            throw new IllegalArgumentException(
                    "processedSteps must be non-negative, got: " + processedSteps);
        }
    }
}
