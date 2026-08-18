package monada.neuron.context;

/**
 * Explicit resource limits shared by all work performed in one cognitive cycle.
 *
 * @param maxSteps maximum completed node-processing calls allowed in the cycle
 * @param maxSignals maximum accepted input, emission, and delivery occurrences
 * @param maxTraceEntries maximum retained deterministic trace entries; zero disables retention
 */
public record CognitiveBudget(int maxSteps, int maxSignals, int maxTraceEntries) {

    /** Validates all bounded-cycle capacities. */
    public CognitiveBudget {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive, got: " + maxSteps);
        }
        if (maxSignals <= 0) {
            throw new IllegalArgumentException("maxSignals must be positive, got: " + maxSignals);
        }
        if (maxTraceEntries < 0) {
            throw new IllegalArgumentException(
                    "maxTraceEntries must be non-negative, got: " + maxTraceEntries);
        }
    }
}
