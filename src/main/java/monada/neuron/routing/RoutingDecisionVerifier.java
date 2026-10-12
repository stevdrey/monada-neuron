package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;

/**
 * Policy-specific verification of a {@link RoutingDecision} (Forge Routing Contract v1, section 18).
 *
 * <p>A verifier is chosen by the trusted host composition, through a {@link TrustedRoutingProfile}; it is never chosen
 * from a decision or a policy. It runs only after the stage's mandatory validation accepted the decision, so provenance
 * binding, request-derived admission and hard eligibility are already established and cannot be relaxed by a verifier.
 * It checks what is specific to one policy: the ordering, the explanation, truncation, the tier cap and the policy's own
 * configuration and state definition.
 *
 * <p>Implementations are immutable and stateless, perform no I/O and read neither a clock nor randomness. A verifier
 * belongs to the trusted host boundary: it protects against a faulty or untrusted policy only to the extent it is
 * implemented correctly, not against a host that installs a permissive one.
 */
public interface RoutingDecisionVerifier {

    /** Id of the policy this verifier checks. */
    String policyId();

    /** Version of the policy this verifier checks. */
    String policyVersion();

    /** The {@link RoutingDecision#CONTRACT_VERSION} whose semantics this verifier checks. */
    String decisionContractVersion();

    /**
     * Verifies a decision that passed the mandatory validation.
     *
     * @param decision the policy's decision
     * @param input the resolved inputs the policy decided on
     * @param report the eligibility filter's report for {@code input}, computed once by the stage
     * @throws IllegalArgumentException if {@code report} was computed for another catalog than {@code input}'s
     * @throws IllegalStateException if the decision disagrees with what this verifier independently derives
     */
    void verify(RoutingDecision decision, RoutingInput input, EligibilityReport report);
}
