package monada.neuron.routing;

import java.util.Objects;

/**
 * Common provenance of every decision variant.
 *
 * @param decisionRef identity of the decision
 * @param catalogVersion catalog snapshot that was evaluated
 * @param policyId routing policy id
 * @param policyVersion routing policy version
 * @param cutoff request cutoff
 * @param state identity of the preference snapshot
 * @param validation admission result of that snapshot
 * @param parameters configuration of the policy that decided
 */
public record Provenance(
        DecisionRef decisionRef,
        String catalogVersion,
        String policyId,
        String policyVersion,
        long cutoff,
        StateBinding state,
        StateValidation validation,
        PolicyParameters parameters) {

    /** Validates parts. */
    public Provenance {
        Objects.requireNonNull(decisionRef, "decisionRef must not be null");
        RoutingTokens.require(catalogVersion, "catalogVersion");
        RoutingTokens.require(policyId, "policyId");
        RoutingTokens.require(policyVersion, "policyVersion");
        if (cutoff < 0) {
            throw new IllegalArgumentException("cutoff must be non-negative, got: " + cutoff);
        }
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(validation, "validation must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
    }
}
