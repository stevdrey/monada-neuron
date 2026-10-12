package monada.neuron.routing.outcome;

/**
 * Result of the worker invocation, kept apart from acceptance: {@code COMPLETED} means the worker finished, not that
 * its work was accepted. {@code UNAVAILABLE} and {@code TIMED_OUT} are environmental and say nothing about the route.
 */
public enum InvocationStatus {
    COMPLETED, FAILED, CANCELLED, UNAVAILABLE, TIMED_OUT;

    /** True for failures of the environment rather than of the generated work. */
    public boolean environmental() {
        return this == UNAVAILABLE || this == TIMED_OUT;
    }
}
