package monada.neuron.routing;

import monada.neuron.context.HostExecutionContext;

import java.util.Optional;

/**
 * Host-implemented port that maps the cycle's opaque {@link HostExecutionContext} to routing inputs, the same
 * pattern as {@code PerceptionCapability}. Neuron never reads the context itself.
 */
@FunctionalInterface
public interface RoutingInputResolver {

    /**
     * Resolves the inputs for one execution.
     *
     * @param context the cycle's host context
     * @return the inputs, or empty when the host cannot resolve them
     */
    Optional<RoutingInput> resolve(HostExecutionContext context);
}
