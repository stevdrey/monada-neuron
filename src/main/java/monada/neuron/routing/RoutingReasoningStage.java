package monada.neuron.routing;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.Hypothesis;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.Proposition;
import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteEligibilityFilter;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Optional {@code REASONING} source stage that runs a {@link RoutingPolicy} for the cycle's host context.
 *
 * <p>It is a source (like {@code PerceptionCognitiveStage}): it must be the first stage, runs with empty initial
 * Signals and cannot be combined with a perception source in the same cycle. It holds no per-cycle state, starts no
 * cycle and never executes the route. A selected route becomes one hypothesis whose {@link Proposition#code()} is the
 * zero-based index of the route in the catalog's canonical order; that code is meaningful only together with the
 * decision's {@code catalogVersion}.
 *
 * <p>The stage only composes: it decides once, evaluates the hard eligibility filter once and hands that report to two
 * collaborators (Forge Routing Contract v1, sections 17.5 and 18). {@link RoutingDecisionValidator}, owned by the stage,
 * always checks provenance, request-derived state admission and eligibility, so no policy or verifier can hand off a
 * hypothesis for an ineligible route; it cannot be replaced, disabled or skipped. A {@link RoutingDecisionVerifier} then
 * checks what is specific to the policy:
 *
 * <ul>
 *   <li>{@linkplain #RoutingReasoningStage(RoutingPolicy, TrustedRoutingProfile, RoutingInputResolver, int) with a
 *       trusted profile}, the host chose the verifier and its independent configuration; the policy's decision and the
 *       parameters and cohort bucket it records are claims to verify, not sources of truth;
 *   <li>{@linkplain #RoutingReasoningStage(RoutingPolicy, RoutingInputResolver, int) legacy}, the {@code route-lex/1}
 *       verifier is applied to every policy and takes the parameters and cohort bucket from the decision itself, so
 *       the policy chooses the configuration it is checked against. That behavior is unchanged and is not independent
 *       verification.
 * </ul>
 */
public final class RoutingReasoningStage implements CognitiveStage {

    private final RouteEligibilityFilter filter = new RouteEligibilityFilter();
    private final RoutingDecisionValidator validator = new RoutingDecisionValidator();
    private final RoutingDecisionVerifier verifier;
    private final RoutingPolicy policy;
    private final RoutingInputResolver resolver;
    private final int domain;

    /**
     * Creates a stage with the legacy verification: the {@code route-lex/1} ordering is recomputed from the parameters
     * and cohort bucket the decision itself records, for any policy. Prefer
     * {@link #RoutingReasoningStage(RoutingPolicy, TrustedRoutingProfile, RoutingInputResolver, int)}, which verifies
     * against host-supplied configuration.
     *
     * @param policy the routing policy
     * @param resolver host adapter resolving routing inputs from the host context
     * @param domain non-negative {@link Proposition} domain the host configures for routing
     */
    public RoutingReasoningStage(RoutingPolicy policy, RoutingInputResolver resolver, int domain) {
        this(policy, LexicographicDecisionVerifier.selfReported(), resolver, domain);
    }

    /**
     * Creates a stage whose decisions are verified by the host-selected verifier of a trusted profile, after the
     * mandatory validation.
     *
     * @param policy the routing policy
     * @param profile the host's binding of the policy identity, decision contract and verifier
     * @param resolver host adapter resolving routing inputs from the host context
     * @param domain non-negative {@link Proposition} domain the host configures for routing
     * @throws IllegalArgumentException if the policy's id or version differs from the profile's, or {@code domain} is
     *         negative
     */
    public RoutingReasoningStage(
            RoutingPolicy policy, TrustedRoutingProfile profile, RoutingInputResolver resolver, int domain) {
        this(policy, Objects.requireNonNull(profile, "profile must not be null").verifier(), resolver, domain);
        if (!profile.policyId().equals(policy.policyId()) || !profile.policyVersion().equals(policy.policyVersion())) {
            throw new IllegalArgumentException("the profile hosts policy " + profile.policyId() + "/"
                    + profile.policyVersion() + " but the stage was given " + policy.policyId() + "/"
                    + policy.policyVersion());
        }
    }

    private RoutingReasoningStage(
            RoutingPolicy policy, RoutingDecisionVerifier verifier, RoutingInputResolver resolver, int domain) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        if (domain < 0) {
            throw new IllegalArgumentException("domain must be non-negative, got: " + domain);
        }
        this.domain = domain;
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.REASONING;
    }

    @Override
    public boolean isSource() {
        return true;
    }

    /**
     * Resolves the inputs and decides.
     *
     * @throws IllegalArgumentException if input Signals are supplied
     * @throws IllegalStateException if the cycle has no host context, the resolver cannot resolve it, or the policy
     *         returns a decision that disagrees with the request, catalog, preference, policy identity or the hard
     *         eligibility filter
     */
    @Override
    public RoutingCognitiveStageResult execute(
            PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
        Objects.requireNonNull(monad, "monad must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (!Objects.requireNonNull(inputSignals, "inputSignals must not be null").isEmpty()) {
            throw new IllegalArgumentException("routing source stage must not receive input signals");
        }
        var hostContext = context.hostContext()
                .orElseThrow(() -> new IllegalStateException("routing needs a host execution context"));
        RoutingInput input = Objects.requireNonNull(resolver.resolve(hostContext), "resolver result must not be null")
                .orElseThrow(() -> new IllegalStateException("the host could not resolve routing inputs"));
        RoutingDecision decision = Objects.requireNonNull(
                policy.decide(input.request(), input.catalog(), input.preference()), "decision must not be null");
        EligibilityReport report = filter.evaluate(input.request(), input.catalog());
        validator.validate(decision, input, policy, report);
        verifier.verify(decision, input, report);
        if (decision instanceof RoutingDecision.Selected selected) {
            List<CatalogEntry> entries = input.catalog().entries();
            int index = 0;
            while (!entries.get(index).key().equals(selected.route())) {
                index++;
            }
            var hypothesis = new Hypothesis(0, new Proposition(domain, index), List.of());
            return new RoutingCognitiveStageResult(
                    decision, new HypothesisSet(List.of(hypothesis), HypothesisLimits.DEFAULT));
        }
        return new RoutingCognitiveStageResult(decision, HypothesisSet.EMPTY);
    }
}
