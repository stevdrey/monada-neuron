package monada.neuron.routing;

import java.util.Objects;

/**
 * The host-chosen binding of one policy identity, the decision contract it targets and the verifier that checks its
 * decisions (Forge Routing Contract v1, section 18).
 *
 * <p>The host builds it from its own configuration, never from a decision or from data a policy returns. Construction
 * fails closed and deterministically: a missing verifier, a verifier for another policy or contract, or a contract
 * this library does not implement is rejected before any decision exists. The profile checks only these declared values;
 * it cannot inspect a policy's ordering, so the host must declare a successor contract for policies the v1 decision
 * types cannot represent. A profile is immutable; reuse one across
 * stage invocations.
 *
 * @param policyId id of the policy the stage hosts
 * @param policyVersion version of that policy
 * @param decisionContractVersion {@link RoutingDecision#CONTRACT_VERSION} the policy's decisions target
 * @param verifier policy-specific verifier selected by the host
 */
public record TrustedRoutingProfile(
        String policyId, String policyVersion, String decisionContractVersion, RoutingDecisionVerifier verifier) {

    /** Validates parts and their agreement. */
    public TrustedRoutingProfile {
        RoutingTokens.require(policyId, "policyId");
        RoutingTokens.require(policyVersion, "policyVersion");
        RoutingTokens.require(decisionContractVersion, "decisionContractVersion");
        Objects.requireNonNull(verifier, "verifier must not be null");
        if (!RoutingDecision.CONTRACT_VERSION.equals(decisionContractVersion)) {
            throw new IllegalArgumentException("unsupported routing decision contract " + decisionContractVersion
                    + ": only " + RoutingDecision.CONTRACT_VERSION + " is implemented, and a policy with other "
                    + "ordering semantics needs a versioned successor of the decision types");
        }
        if (!policyId.equals(verifier.policyId()) || !policyVersion.equals(verifier.policyVersion())) {
            throw new IllegalArgumentException("verifier checks policy " + verifier.policyId() + "/"
                    + verifier.policyVersion() + " but the profile hosts " + policyId + "/" + policyVersion);
        }
        if (!decisionContractVersion.equals(verifier.decisionContractVersion())) {
            throw new IllegalArgumentException("verifier checks decision contract " + verifier.decisionContractVersion()
                    + " but the profile targets " + decisionContractVersion);
        }
    }

    /**
     * Profile for the reference policy identity, verified against configuration the host supplies separately from the
     * policy object.
     *
     * @param parameters the effective parameters the host configured
     * @param definition the state definition the host configured
     */
    public static TrustedRoutingProfile lexicographic(PolicyParameters parameters, RoutingStateDefinition definition) {
        return new TrustedRoutingProfile(LexicographicRoutingPolicy.POLICY_ID, LexicographicRoutingPolicy.POLICY_VERSION,
                RoutingDecision.CONTRACT_VERSION, LexicographicDecisionVerifier.trusted(parameters, definition));
    }

    /**
     * Convenience profile for the reference policy that reads the expected configuration from the policy instance
     * itself. The trust therefore equals that instance's own configuration: it detects a different policy (or a decision
     * recording other values) but not a host that composes a wrongly configured instance. Prefer
     * {@link #lexicographic(PolicyParameters, RoutingStateDefinition)} when the configuration comes from a separate source.
     *
     * @param policy the host's reference policy
     */
    public static TrustedRoutingProfile lexicographic(LexicographicRoutingPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        return lexicographic(policy.parameters(), policy.definition());
    }
}
