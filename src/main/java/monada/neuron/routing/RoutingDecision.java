package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded routing advice for one stage: exactly one of {@link Selected}, {@link Abstain} or
 * {@link NoEligibleRoute}.
 *
 * <p>A decision recommends; it reserves nothing, grants no authorization and is never an {@code ActionStatus}.
 * Forge must revalidate authorization, availability and quota before executing.
 */
public sealed interface RoutingDecision
        permits RoutingDecision.Selected, RoutingDecision.Abstain, RoutingDecision.NoEligibleRoute {

    /** Maximum ranked candidates retained in a decision. */
    int MAX_CANDIDATES = 8;

    /** Common provenance. */
    Provenance provenance();

    /** Exclusion reasons, one entry per excluded catalog route, in canonical route order. */
    List<EligibilityReport.Exclusion> exclusions();

    /**
     * Exactly one recommended route.
     *
     * @param provenance common provenance
     * @param route the recommended route
     * @param executionMode the permitted mode chosen by the eligibility filter
     * @param basis what separated the winner from the runner-up
     * @param overflowUsed whether the route is an overflow route the host permitted
     * @param candidates ranked eligible routes, at most {@value #MAX_CANDIDATES}; the first is {@code route}
     * @param candidatesTruncated whether more eligible routes exist than {@code candidates} lists
     * @param rulesApplied rules that took part in ordering, in rule order
     * @param rulesSkipped optional rules that were skipped, in rule order
     * @param exclusions reasons for the excluded routes only
     * @param cohortBinding cohort computed from the request's features
     */
    record Selected(
            Provenance provenance,
            RouteKey route,
            String executionMode,
            Basis basis,
            boolean overflowUsed,
            List<RankedCandidate> candidates,
            boolean candidatesTruncated,
            List<RoutingRule> rulesApplied,
            List<RoutingRule> rulesSkipped,
            List<EligibilityReport.Exclusion> exclusions,
            CohortBinding cohortBinding) implements RoutingDecision {

        /** Validates and copies. */
        public Selected {
            Objects.requireNonNull(provenance, "provenance must not be null");
            Objects.requireNonNull(route, "route must not be null");
            RoutingTokens.require(executionMode, "executionMode");
            Objects.requireNonNull(basis, "basis must not be null");
            candidates = ranked(candidates);
            if (candidates.isEmpty() || !candidates.getFirst().key().equals(route)) {
                throw new IllegalArgumentException("the first ranked candidate must be the selected route");
            }
            rulesApplied = List.copyOf(Objects.requireNonNull(rulesApplied, "rulesApplied must not be null"));
            rulesSkipped = List.copyOf(Objects.requireNonNull(rulesSkipped, "rulesSkipped must not be null"));
            exclusions = excluded(exclusions);
            Objects.requireNonNull(cohortBinding, "cohortBinding must not be null");
            if (!(provenance.validation() instanceof StateValidation.Compatible)) {
                throw new IllegalArgumentException("a selected decision needs a compatible state");
            }
        }
    }

    /**
     * The policy declines to advise although at least one route is eligible.
     *
     * @param provenance common provenance
     * @param reason typed reason
     * @param eligible every eligible route in canonical order
     * @param candidates ranked eligible routes (empty for {@link AbstainReason#STATE_INCOMPATIBLE}, where ordering
     *         never runs), at most {@value #MAX_CANDIDATES}
     * @param candidatesTruncated whether more eligible routes exist than {@code candidates} lists
     * @param exclusions reasons for the excluded routes only
     */
    record Abstain(
            Provenance provenance,
            AbstainReason reason,
            List<RouteKey> eligible,
            List<RankedCandidate> candidates,
            boolean candidatesTruncated,
            List<EligibilityReport.Exclusion> exclusions) implements RoutingDecision {

        /** Validates and copies. */
        public Abstain {
            Objects.requireNonNull(provenance, "provenance must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            eligible = List.copyOf(Objects.requireNonNull(eligible, "eligible must not be null"));
            if (eligible.isEmpty() || eligible.size() > RouteCatalog.MAX_ROUTES) {
                throw new IllegalArgumentException("an abstention needs 1 to " + RouteCatalog.MAX_ROUTES
                        + " eligible routes, got: " + eligible.size());
            }
            candidates = ranked(candidates);
            exclusions = excluded(exclusions);
            switch (reason) {
                case STATE_INCOMPATIBLE -> {
                    if (!(provenance.validation() instanceof StateValidation.Incompatible)) {
                        throw new IllegalArgumentException("STATE_INCOMPATIBLE needs an incompatible validation");
                    }
                    if (!candidates.isEmpty()) {
                        throw new IllegalArgumentException("STATE_INCOMPATIBLE ranks no candidates");
                    }
                }
                case POLICY_TRADEOFF_UNRESOLVED -> {
                    if (!(provenance.validation() instanceof StateValidation.Compatible)) {
                        throw new IllegalArgumentException("POLICY_TRADEOFF_UNRESOLVED needs a compatible state");
                    }
                    if (candidates.isEmpty()) {
                        throw new IllegalArgumentException("POLICY_TRADEOFF_UNRESOLVED needs ranked candidates");
                    }
                }
            }
        }
    }

    /**
     * The hard constraints exclude every route.
     *
     * @param provenance common provenance; its validation is {@link StateValidation.NotEvaluated}
     * @param exclusions one structured reason for every catalog route
     */
    record NoEligibleRoute(Provenance provenance, List<EligibilityReport.Exclusion> exclusions)
            implements RoutingDecision {

        /** Validates and copies. */
        public NoEligibleRoute {
            Objects.requireNonNull(provenance, "provenance must not be null");
            exclusions = excluded(exclusions);
            if (!(provenance.validation() instanceof StateValidation.NotEvaluated)) {
                throw new IllegalArgumentException("state is not consulted when no route is eligible");
            }
        }
    }

    private static List<RankedCandidate> ranked(List<RankedCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                    "at most " + MAX_CANDIDATES + " ranked candidates, got: " + candidates.size());
        }
        var keys = new HashSet<RouteKey>();
        for (int i = 0; i < candidates.size(); i++) {
            RankedCandidate candidate = Objects.requireNonNull(candidates.get(i), "candidate must not be null");
            if (candidate.rank() != i) {
                throw new IllegalArgumentException("candidate rank must equal its position, got "
                        + candidate.rank() + " at " + i);
            }
            if (!keys.add(candidate.key())) {
                throw new IllegalArgumentException("duplicate ranked candidate: " + candidate.key());
            }
        }
        return List.copyOf(candidates);
    }

    private static List<EligibilityReport.Exclusion> excluded(List<EligibilityReport.Exclusion> exclusions) {
        Objects.requireNonNull(exclusions, "exclusions must not be null");
        if (exclusions.size() > RouteCatalog.MAX_ROUTES) {
            throw new IllegalArgumentException("at most " + RouteCatalog.MAX_ROUTES
                    + " exclusions, got: " + exclusions.size());
        }
        var keys = new HashSet<RouteKey>();
        for (EligibilityReport.Exclusion exclusion : exclusions) {
            if (!keys.add(Objects.requireNonNull(exclusion, "exclusion must not be null").key())) {
                throw new IllegalArgumentException("duplicate exclusion for " + exclusion.key());
            }
        }
        return List.copyOf(exclusions);
    }
}
