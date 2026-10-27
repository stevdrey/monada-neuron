package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteEligibilityFilter;

import java.util.Objects;

/**
 * Decides once and checks the decision the way a trusted {@link RoutingReasoningStage} does, without a cognitive cycle:
 * the hard eligibility filter runs once, {@link RoutingDecisionValidator} checks provenance, state admission and
 * eligibility against the profile's identity, then the profile's verifier checks what is specific to the policy.
 *
 * <p>It is for callers that need verified advice outside a cycle, such as a scenario preview. It holds no per-call
 * state, executes nothing and grants nothing: a returned decision is still advice the host must recheck before any
 * execution.
 */
public final class TrustedRoutingAdvice {

    private final RouteEligibilityFilter filter = new RouteEligibilityFilter();
    private final RoutingDecisionValidator validator = new RoutingDecisionValidator();

    /**
     * Decides and verifies.
     *
     * @param policy the routing policy
     * @param profile the host's binding of the policy identity, decision contract and verifier
     * @param input the resolved inputs
     * @return the policy's decision, unchanged, after it passed both checks
     * @throws IllegalArgumentException if the policy's id or version differs from the profile's
     * @throws IllegalStateException if the decision disagrees with the inputs, the profile identity, the hard
     *         eligibility filter or the profile's verifier
     */
    public RoutingDecision decide(RoutingPolicy policy, TrustedRoutingProfile profile, RoutingInput input) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(input, "input must not be null");
        if (!profile.policyId().equals(policy.policyId()) || !profile.policyVersion().equals(policy.policyVersion())) {
            throw new IllegalArgumentException("the profile hosts policy " + profile.policyId() + "/"
                    + profile.policyVersion() + " but was given " + policy.policyId() + "/" + policy.policyVersion());
        }
        RoutingDecision decision = Objects.requireNonNull(
                policy.decide(input.request(), input.catalog(), input.preference()), "decision must not be null");
        EligibilityReport report = filter.evaluate(input.request(), input.catalog());
        validator.validate(decision, input, profile.policyId(), profile.policyVersion(), report);
        profile.verifier().verify(decision, input, report);
        return decision;
    }
}
