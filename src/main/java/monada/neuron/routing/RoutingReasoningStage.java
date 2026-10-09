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
 * cycle and never executes the route. It re-verifies a selected route against the hard eligibility filter, so a
 * custom policy cannot hand off a hypothesis for an ineligible route. A selected route becomes one hypothesis whose
 * {@link Proposition#code()} is the zero-based index of the route in the catalog's canonical order; that code is
 * meaningful only together with the decision's {@code catalogVersion}.
 */
public final class RoutingReasoningStage implements CognitiveStage {

    private final RouteEligibilityFilter filter = new RouteEligibilityFilter();
    private final RoutingPolicy policy;
    private final RoutingInputResolver resolver;
    private final int domain;

    /**
     * Creates a stage.
     *
     * @param policy the routing policy
     * @param resolver host adapter resolving routing inputs from the host context
     * @param domain non-negative {@link Proposition} domain the host configures for routing
     */
    public RoutingReasoningStage(RoutingPolicy policy, RoutingInputResolver resolver, int domain) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
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
     *         selects a route that the hard eligibility filter does not accept (or with another mode or overflow flag)
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
        if (decision instanceof RoutingDecision.Selected selected) {
            verifyEligible(selected, input);
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

    /** Re-verifies a selection against the hard eligibility filter, so an injected policy cannot bypass it. */
    private void verifyEligible(RoutingDecision.Selected selected, RoutingInput input) {
        EligibilityReport report = filter.evaluate(input.request(), input.catalog());
        EligibilityReport.EligibleRoute eligible = report.eligible().stream()
                .filter(route -> route.key().equals(selected.route()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "policy selected a route that is not eligible for the request: " + selected.route()));
        if (!eligible.executionMode().equals(selected.executionMode())
                || eligible.overflow() != selected.overflowUsed()) {
            throw new IllegalStateException("policy decision disagrees with the eligibility filter for "
                    + selected.route() + ": mode " + selected.executionMode() + ", overflow "
                    + selected.overflowUsed() + " instead of " + eligible.executionMode() + ", "
                    + eligible.overflow());
        }
    }
}
