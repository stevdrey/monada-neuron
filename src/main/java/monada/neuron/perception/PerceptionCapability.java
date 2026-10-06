package monada.neuron.perception;

/**
 * Neuron-owned boundary for turning one request-scoped host observation into bounded signals.
 *
 * <p>Implementations own transport, encoding, model, and provider details, and resolve the observation
 * from the request's host context. Neuron prescribes no universal text or task encoder. Expected outcomes
 * are returned through {@link PerceptionResult}; an unexpected runtime failure remains an operational
 * failure of the calling cognitive stage.
 */
@FunctionalInterface
public interface PerceptionCapability {

    /** Performs one bounded perception request; signals must be ordered deterministically. */
    PerceptionResult perceive(PerceptionRequest request);
}
