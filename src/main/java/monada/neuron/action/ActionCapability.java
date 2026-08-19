package monada.neuron.action;

/**
 * Neuron-owned boundary for requesting one external action capability.
 *
 * <p>Implementations own transport, latency, device, and provider details. Expected execution
 * outcomes are returned through {@link ActionResult}; an unexpected runtime failure remains an
 * operational failure of the calling cognitive stage.
 */
@FunctionalInterface
public interface ActionCapability {

    /** Executes one ordered, bounded action request. */
    ActionResult execute(ActionRequest request);
}
