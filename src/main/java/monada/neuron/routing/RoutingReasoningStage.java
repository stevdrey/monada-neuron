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

import java.util.EnumSet;
import java.util.LinkedHashSet;
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
 *
 * <p>The stage enforces the fixed ordering of Forge Routing Contract v1, section 3.3, for every policy it hosts: it
 * recomputes the ranking from the decision's recorded parameters and rejects a decision that differs. A policy with
 * other ordering rules would be a new policy version and needs its own stage.
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
     * eligibility filter and the contract ordering (recomputed from its recorded parameters), or its catalog-relative codes and audit data would mislead.
     *
     * <p>The recorded admission result ({@code validation}) is recomputed only partially, because the port does not
     * expose the policy's state definition: scope, evaluation policy, watermark and mapping version are checked in both
     * directions, the feature schema only in one (a mismatch can also stem from the policy's own mapping). The cohort
     * bucket is not re-derived either.
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
        verifyState(provenance.validation(), provenance.parameters(), input);
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
                if (abstain.reason() == AbstainReason.POLICY_TRADEOFF_UNRESOLVED) {
                    verifyExplanation(abstain.candidates(), abstain.candidatesTruncated(), report);
                    verifyTradeoff(abstain, report, input);
                }
            }
            case RoutingDecision.Selected selected -> {
                verifySelected(selected, report);
                verifyExplanation(selected.candidates(), selected.candidatesTruncated(), report);
                verifySelectedRanking(selected, report, input);
            }
        }
        if (!decision.exclusions().equals(report.excluded())) {
            throw new IllegalStateException("policy decision exclusions disagree with the eligibility filter");
        }
    }

    /**
     * The recorded validation must agree with the admission keys that the request, the preference and the recorded
     * policy parameters decide: scope, evaluation policy, watermark and mapping version in both directions, and the
     * feature schema in one direction (a mismatch can also stem from the policy's own mapping, which the port does not
     * expose).
     */
    private void verifyState(StateValidation validation, PolicyParameters parameters, RoutingInput input) {
        RoutingPreference preference = input.preference();
        var expected = EnumSet.noneOf(StateMismatch.class);
        expected.addAll(preference.mismatches(input.request()));
        if (!preference.mappingVersion().equals(parameters.stateMappingVersion())) {
            expected.add(StateMismatch.MAPPING_VERSION_MISMATCH);
        }
        switch (validation) {
            case StateValidation.Compatible compatible -> {
                if (!expected.isEmpty()) {
                    throw new IllegalStateException("policy decision accepted a preference snapshot that conflicts "
                            + "with the request or the policy mapping: " + expected);
                }
            }
            case StateValidation.Incompatible incompatible -> {
                if (!incompatible.reasons().containsAll(expected)) {
                    throw new IllegalStateException("policy decision omits state mismatches the request implies: "
                            + expected);
                }
                for (StateMismatch reason : incompatible.reasons()) {
                    if (reason != StateMismatch.FEATURE_SCHEMA_MISMATCH && !expected.contains(reason)) {
                        throw new IllegalStateException("policy decision reports a state mismatch that does not "
                                + "exist: " + reason);
                    }
                }
            }
            case StateValidation.NotEvaluated notEvaluated -> { }
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

    /** Every ranked candidate must be eligible, and the explanation must be the bounded prefix the filter implies. */
    private void verifyExplanation(List<RankedCandidate> candidates, boolean truncated, EligibilityReport report) {
        for (RankedCandidate candidate : candidates) {
            if (report.eligible().stream().noneMatch(route -> route.key().equals(candidate.key()))) {
                throw new IllegalStateException(
                        "policy ranked a route that is not eligible for the request: " + candidate.key());
            }
        }
        int eligible = report.eligible().size();
        if (candidates.size() != Math.min(RoutingDecision.MAX_CANDIDATES, eligible)
                || truncated != eligible > RoutingDecision.MAX_CANDIDATES) {
            throw new IllegalStateException("policy ranking lists " + candidates.size() + " candidates (truncated: "
                    + truncated + ") for " + eligible + " eligible routes");
        }
    }

    /**
     * A {@code Selected} decision must equal the contract ordering recomputed from its recorded parameters and cohort
     * bucket (the bucket itself cannot be re-derived), and its winner must not exceed the recorded tier cap.
     */
    private void verifySelectedRanking(RoutingDecision.Selected selected, EligibilityReport report, RoutingInput input) {
        PolicyParameters parameters = selected.provenance().parameters();
        LexicographicRanking.Result expected = new LexicographicRanking(
                parameters.minSupportingObservations(), parameters.objective())
                .rank(input.request(), input.catalog(), report.eligible(), input.preference(),
                        selected.cohortBinding().cohortBucket());
        boolean sameRanking = expected.order().getFirst().route().key().equals(selected.route())
                && expected.candidates().equals(selected.candidates())
                && expected.truncated() == selected.candidatesTruncated()
                && expected.applied().equals(selected.rulesApplied())
                && expected.skipped().equals(selected.rulesSkipped())
                && expected.candidates().getFirst().placement().basis() == selected.basis();
        if (!sameRanking) {
            throw new IllegalStateException("policy ranking disagrees with the recomputed ranking: expected "
                    + expected.candidates() + " (applied " + expected.applied() + ", skipped " + expected.skipped()
                    + "), got " + selected.candidates() + " (applied " + selected.rulesApplied() + ", skipped "
                    + selected.rulesSkipped() + ", basis " + selected.basis() + ")");
        }
        if (exceedsCap(expected, parameters)) {
            throw new IllegalStateException("policy selected " + selected.route() + " (tier "
                    + expected.order().getFirst().tier() + ") above the recorded tier cap "
                    + parameters.maxAutoSelectTier());
        }
    }

    /**
     * A tier-cap abstention is the only tradeoff trigger. No cohort binding is recorded for it, so the ranking is
     * recomputed for every cohort bucket the preference holds for the stage kind, and for none; the abstention is
     * accepted when some bucket reproduces its ranking and the winner then exceeds the recorded cap.
     */
    private void verifyTradeoff(RoutingDecision.Abstain abstain, EligibilityReport report, RoutingInput input) {
        PolicyParameters parameters = abstain.provenance().parameters();
        var ranking = new LexicographicRanking(parameters.minSupportingObservations(), parameters.objective());
        var buckets = new LinkedHashSet<String>();
        buckets.add(null);
        for (CohortPreference cohort : input.preference().cohorts()) {
            if (cohort.stageKind().equals(input.request().stageKind())) {
                buckets.add(cohort.cohortBucket());
            }
        }
        boolean sameRanking = false;
        for (String bucket : buckets) {
            LexicographicRanking.Result expected = ranking.rank(
                    input.request(), input.catalog(), report.eligible(), input.preference(), bucket);
            if (expected.candidates().equals(abstain.candidates())
                    && expected.truncated() == abstain.candidatesTruncated()) {
                sameRanking = true;
                if (exceedsCap(expected, parameters)) {
                    return;
                }
            }
        }
        throw new IllegalStateException(sameRanking
                ? "policy abstained on the tier cap but the winner does not exceed the recorded cap "
                        + parameters.maxAutoSelectTier()
                : "policy ranking disagrees with the recomputed ranking: got " + abstain.candidates());
    }

    private boolean exceedsCap(LexicographicRanking.Result ranking, PolicyParameters parameters) {
        return parameters.maxAutoSelectTier().isPresent()
                && ranking.order().getFirst().tier() > parameters.maxAutoSelectTier().getAsInt();
    }
}
