package monada.neuron.reasoning.concept;

import monada.neuron.context.HostExecutionContext;

import java.util.Optional;

/**
 * Host-implemented port that maps the cycle's opaque {@link HostExecutionContext} to sampled windows, the same
 * pattern as {@code RoutingInputResolver} and {@code PerceptionCapability}. Neuron never reads the context itself.
 *
 * <p>The resolver is shared by every execution of a runtime, so it must answer from the context it receives, not
 * from state left by a previous call.
 */
@FunctionalInterface
public interface WaveInputResolver {

    /**
     * Resolves the windows of one execution.
     *
     * @param context the cycle's host context
     * @return the windows, or empty when this execution does not opt in to wave cognition
     */
    Optional<WaveCognitionInput> resolve(HostExecutionContext context);
}
