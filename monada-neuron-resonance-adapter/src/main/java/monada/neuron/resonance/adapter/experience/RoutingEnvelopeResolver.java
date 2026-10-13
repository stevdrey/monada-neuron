package monada.neuron.resonance.adapter.experience;

import java.util.Optional;

/**
 * Host port that turns the opaque envelope reference recorded on a Store stage event into its {@link RoutingEnvelope}.
 * The host implements it over its own storage; Neuron never fetches by itself and never writes the Store.
 */
@FunctionalInterface
public interface RoutingEnvelopeResolver {

    /**
     * Resolves one envelope.
     *
     * @param scopeId scope of the stage that referenced the envelope
     * @param envelopeRef host-issued opaque reference
     * @return the envelope, or empty when the host has none for this reference
     */
    Optional<RoutingEnvelope> resolve(String scopeId, String envelopeRef);
}
