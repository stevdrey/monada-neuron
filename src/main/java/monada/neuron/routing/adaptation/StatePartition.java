package monada.neuron.routing.adaptation;

import monada.neuron.routing.token.OpaqueTokens;

/**
 * The partition one {@link RoutingStateStore} learns for: one scope under one evaluation policy. Together with the
 * definition's {@code mappingVersion} and feature schema it is the admission key of every snapshot the store
 * publishes, so feedback of another scope or evaluation policy can never reach its Nodes.
 *
 * @param scopeId scope token
 * @param evaluationPolicyId evaluation policy id
 * @param evaluationPolicyVersion evaluation policy version
 */
public record StatePartition(String scopeId, String evaluationPolicyId, String evaluationPolicyVersion) {

    /** Validates the tokens. */
    public StatePartition {
        OpaqueTokens.require(scopeId, "scopeId");
        OpaqueTokens.require(evaluationPolicyId, "evaluationPolicyId");
        OpaqueTokens.require(evaluationPolicyVersion, "evaluationPolicyVersion");
    }
}
