package monada.neuron.resonance.adapter.experience;

import monada.neuron.routing.catalog.BillingMode;
import monada.neuron.routing.features.Feature;
import monada.neuron.routing.token.OpaqueTokens;

import java.util.Objects;

/**
 * Immutable snapshot of the worker description a routed stage was executed with, as carried inside a
 * {@link RoutingEnvelope}. It is compared field by field with the descriptor the Store recorded; an absent value matches
 * only an absent value.
 *
 * @param worker worker token
 * @param provider provider token, or unknown
 * @param model model token, or unknown
 * @param effort effort token, or unknown
 * @param billingMode billing mode
 */
public record ExecutedRouteDescriptor(
        String worker, Feature<String> provider, Feature<String> model, Feature<String> effort,
        BillingMode billingMode) {

    /** Validates tokens. */
    public ExecutedRouteDescriptor {
        OpaqueTokens.require(worker, "worker");
        provider = checked(provider, "provider");
        model = checked(model, "model");
        effort = checked(effort, "effort");
        Objects.requireNonNull(billingMode, "billingMode must not be null");
    }

    private static Feature<String> checked(Feature<String> value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value instanceof Feature.Known<String> known) {
            OpaqueTokens.require(known.value(), name);
        }
        return value;
    }
}
