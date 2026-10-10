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
        verify(decision, input);
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

    /**
     * The decision must describe exactly this request, catalog, preference and policy, and agree with the hard
     * eligibility filter, or its catalog-relative codes and audit data would mislead. The admission result
     * ({@code validation}) is not recomputed: that needs the policy's state definition, which the port does not expose.
     */
    private void verify(RoutingDecision decision, RoutingInput input) {
        Provenance provenance = decision.provenance();
        if (!provenance.catalogVersion().equals(input.catalog().catalogVersion())) {
            throw new IllegalStateException("policy decision refers to catalog " + provenance.catalogVersion()
                    + " but the resolved catalog is " + input.catalog().catalogVersion());
        }
        if (!provenance.decisionRef().equals(DecisionRef.of(input.request()))) {
            throw new IllegalStateException("policy decision refers to another request: " + provenance.decisionRef());
        }
        if (provenance.cutoff() != input.request().cutoff()) {
            throw new IllegalStateException("policy decision records cutoff " + provenance.cutoff()
                    + " but the request cutoff is " + input.request().cutoff());
        }
        if (!provenance.state().equals(input.preference().binding())) {
            throw new IllegalStateException("policy decision records another preference snapshot: "
                    + provenance.state());
        }
        if (!provenance.policyId().equals(policy.policyId())
                || !provenance.policyVersion().equals(policy.policyVersion())) {
            throw new IllegalStateException("policy decision records policy " + provenance.policyId() + "/"
                    + provenance.policyVersion() + " instead of " + policy.policyId() + "/" + policy.policyVersion());
        }
        EligibilityReport report = filter.evaluate(input.request(), input.catalog());
        switch (decision) {
            case RoutingDecision.NoEligibleRoute ignored -> {
                if (!report.noneEligible()) {
                    throw new IllegalStateException("policy reported no eligible route but the eligibility filter "
                            + "accepts " + report.eligible().size());
                }
            }
            case RoutingDecision.Abstain abstain -> {
                if (!abstain.eligible().equals(report.eligible().stream().map(route -> route.key()).toList())) {
                    throw new IllegalStateException("policy abstention lists other eligible routes than the filter");
                }
            }
            case RoutingDecision.Selected selected -> verifySelected(selected, report);
        }
        if (!decision.exclusions().equals(report.excluded())) {
            throw new IllegalStateException("policy decision exclusions disagree with the eligibility filter");
        }
    }

    private void verifySelected(RoutingDecision.Selected selected, EligibilityReport report) {
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
