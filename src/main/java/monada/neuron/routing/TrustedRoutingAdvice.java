package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteEligibilityFilter;

import java.util.Objects;

/**
 * Decides once and checks the decision on the trusted path: the hard eligibility filter runs once,
 * {@link RoutingDecisionValidator} checks provenance, state admission and eligibility against the profile's identity,
 * then the profile's verifier checks what is specific to the policy. The trusted path of {@link RoutingReasoningStage}
 * delegates here, so the composition exists once.
 *
 * <p>It is also for callers that need verified advice outside a cycle, such as a scenario preview. It holds no per-call
 * state, executes nothing and grants nothing: a returned decision is still advice the host must recheck before any
 * execution.
 */
public final class TrustedRoutingAdvice {

    private final RouteEligibilityFilter filter = new RouteEligibilityFilter();
    private final RoutingDecisionValidator validator = new RoutingDecisionValidator();

    /**
     * A decision that passed both checks, with the eligibility report it was checked against.
     *
     * @param decision the policy's decision, unchanged
     * @param report the hard eligibility filter's report for the same request and catalog; its exclusions equal the
     *         decision's
     */
    public record Verified(RoutingDecision decision, EligibilityReport report) {

        /** Requires non-null parts. */
        public Verified {
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(report, "report must not be null");
        }
    }

    /**
     * Decides and verifies.
     *
     * @param policy the routing policy
     * @param profile the host's binding of the policy identity, decision contract and verifier
     * @param input the resolved inputs
     * @return the policy's decision, unchanged, after it passed both checks, with the report it was checked against
     * @throws IllegalArgumentException if the policy's id or version differs from the profile's
     * @throws IllegalStateException if the decision disagrees with the inputs, the profile identity, the hard
     *         eligibility filter or the profile's verifier
     */
    public Verified decide(RoutingPolicy policy, TrustedRoutingProfile profile, RoutingInput input) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(input, "input must not be null");
        profile.requireHosts(policy);
        return decideHosted(policy, profile, input);
    }

    /**
     * Decides and verifies for a composition that checked {@link TrustedRoutingProfile#requireHosts} once when it was
     * built, as {@link RoutingReasoningStage} does: from then on only the profile's identity counts, so a policy that
     * reports another identity later fails the validator's provenance check with an {@link IllegalStateException}.
     */
    Verified decideHosted(RoutingPolicy policy, TrustedRoutingProfile profile, RoutingInput input) {
        RoutingDecision decision = Objects.requireNonNull(
                policy.decide(input.request(), input.catalog(), input.preference()), "decision must not be null");
        EligibilityReport report = filter.evaluate(input.request(), input.catalog());
        // Checked against the profile's identity, not the policy object's, which could report another identity later.
        validator.validate(decision, input, profile.policyId(), profile.policyVersion(), report);
        profile.verifier().verify(decision, input, report);
        return new Verified(decision, report);
    }
}
