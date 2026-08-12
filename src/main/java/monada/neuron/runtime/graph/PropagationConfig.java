package monada.neuron.runtime.graph;

import java.util.Objects;

/**
 * Immutable limits and routing behavior for one signal propagation.
 *
 * @param maxSteps maximum number of node-processing invocations; must be positive
 * @param maxHops maximum directed edges traversed from the start node; zero processes only the
 *                start node
 * @param routingPolicy explicit policy applied to every emitted-signal/target pair
 */
public record PropagationConfig(
        int maxSteps,
        int maxHops,
        SignalRoutingPolicy routingPolicy) {

    /** Validates all propagation bounds and required behavior. */
    public PropagationConfig {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive, got: " + maxSteps);
        }
        if (maxHops < 0) {
            throw new IllegalArgumentException("maxHops must be non-negative, got: " + maxHops);
        }
        Objects.requireNonNull(routingPolicy, "routingPolicy must not be null");
    }

    /**
     * Creates a bounded configuration that routes every emitted signal to every connection.
     *
     * @param maxSteps maximum number of node-processing invocations
     * @param maxHops maximum directed edges traversed from the start node
     * @return route-all propagation configuration
     */
    public static PropagationConfig routeAll(int maxSteps, int maxHops) {
        return new PropagationConfig(maxSteps, maxHops, SignalRoutingPolicy.routeAll());
    }
}
