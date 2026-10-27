package monada.neuron.routing.comparison;

import monada.neuron.routing.RoutingInput;
import monada.neuron.routing.RoutingPolicy;
import monada.neuron.routing.TrustedRoutingProfile;

import java.util.Objects;

/**
 * One side of a preview: the inputs a host would resolve and the trusted policy configuration it would decide them with.
 * Every part is caller-provided and immutable; the comparison reads it and never changes it.
 *
 * @param policy the routing policy
 * @param profile the host's binding of that policy's identity, decision contract and verifier; required, so every
 *         side is verified independently of the policy
 * @param input request, catalog and preference snapshot
 */
public record RoutingScenario(RoutingPolicy policy, TrustedRoutingProfile profile, RoutingInput input) {

    /** Requires non-null parts whose policy identities agree. */
    public RoutingScenario {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(input, "input must not be null");
        if (!profile.policyId().equals(policy.policyId()) || !profile.policyVersion().equals(policy.policyVersion())) {
            throw new IllegalArgumentException("the profile hosts policy " + profile.policyId() + "/"
                    + profile.policyVersion() + " but the scenario was given " + policy.policyId() + "/"
                    + policy.policyVersion());
        }
    }
}
