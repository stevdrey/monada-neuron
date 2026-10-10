package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;

import java.util.ArrayList;
import java.util.Comparator;
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

    /** Exclusion reasons, one entry per excluded catalog route, stored in canonical route order. */
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
     * @param rulesApplied rules that took part in ordering, unique and in {@link RoutingRule} order; must include
     *         the mandatory tier, priority and route-order rules
     * @param rulesSkipped optional rules that were skipped, unique and in rule order; together with the applied
     *         rules they cover every rule exactly once
     * @param exclusions reasons for the excluded routes only
     * @param cohortBinding cohort computed from the request's features; its schema and mapping versions must
     *         agree with the state provenance and the policy parameters
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
            requireRulePartition(rulesApplied, rulesSkipped);
            exclusions = excluded(exclusions);
            requireDisjoint(exclusions, candidates.stream().map(RankedCandidate::key).toList());
            if (candidates.size() + exclusions.size() > RouteCatalog.MAX_ROUTES) {
                throw new IllegalArgumentException("candidates and exclusions come from one catalog of at most "
                        + RouteCatalog.MAX_ROUTES + " routes, got: " + (candidates.size() + exclusions.size()));
            }
            Objects.requireNonNull(cohortBinding, "cohortBinding must not be null");
            if (!(provenance.validation() instanceof StateValidation.Compatible)) {
                throw new IllegalArgumentException("a selected decision needs a compatible state");
            }
            StateBinding state = provenance.state();
            if (!cohortBinding.featureSchemaVersion().equals(state.featureSchemaVersion())
                    || !cohortBinding.mappingVersion().equals(state.mappingVersion())
                    || !cohortBinding.mappingVersion().equals(provenance.parameters().stateMappingVersion())) {
                throw new IllegalArgumentException("the cohort binding disagrees with the state provenance: "
                        + cohortBinding);
            }
        }
    }

    /**
     * The policy declines to advise although at least one route is eligible.
     *
     * @param provenance common provenance
     * @param reason typed reason
     * @param eligible every eligible route, unique; stored in canonical order
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
            Objects.requireNonNull(eligible, "eligible must not be null");
            if (eligible.isEmpty() || eligible.size() > RouteCatalog.MAX_ROUTES) {
                throw new IllegalArgumentException("an abstention needs 1 to " + RouteCatalog.MAX_ROUTES
                        + " eligible routes, got: " + eligible.size());
            }
            var sortedEligible = new ArrayList<>(eligible);
            sortedEligible.forEach(key -> Objects.requireNonNull(key, "eligible route must not be null"));
            sortedEligible.sort(null);
            for (int i = 1; i < sortedEligible.size(); i++) {
                if (sortedEligible.get(i - 1).equals(sortedEligible.get(i))) {
                    throw new IllegalArgumentException("duplicate eligible route: " + sortedEligible.get(i));
                }
            }
            eligible = List.copyOf(sortedEligible);
            candidates = ranked(candidates);
            exclusions = excluded(exclusions);
            requireDisjoint(exclusions, eligible);
            if (eligible.size() + exclusions.size() > RouteCatalog.MAX_ROUTES) {
                throw new IllegalArgumentException("eligible routes and exclusions come from one catalog of at most "
                        + RouteCatalog.MAX_ROUTES + " routes, got: " + (eligible.size() + exclusions.size()));
            }
            for (RankedCandidate candidate : candidates) {
                if (!eligible.contains(candidate.key())) {
                    throw new IllegalArgumentException("ranked candidate is not eligible: " + candidate.key());
                }
            }
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
        var sorted = new ArrayList<>(exclusions);
        sorted.forEach(exclusion -> Objects.requireNonNull(exclusion, "exclusion must not be null"));
        sorted.sort(Comparator.comparing(EligibilityReport.Exclusion::key));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).key().equals(sorted.get(i).key())) {
                throw new IllegalArgumentException("duplicate exclusion for " + sorted.get(i).key());
            }
        }
        return List.copyOf(sorted);
    }

    private static void requireDisjoint(List<EligibilityReport.Exclusion> exclusions, List<RouteKey> eligible) {
        var excluded = new HashSet<RouteKey>();
        exclusions.forEach(exclusion -> excluded.add(exclusion.key()));
        for (RouteKey key : eligible) {
            if (excluded.contains(key)) {
                throw new IllegalArgumentException("a route cannot be both eligible and excluded: " + key);
            }
        }
    }

    private static void requireRulePartition(List<RoutingRule> applied, List<RoutingRule> skipped) {
        requireAscending(applied, "rulesApplied");
        requireAscending(skipped, "rulesSkipped");
        for (RoutingRule rule : skipped) {
            if (rule != RoutingRule.LEARNED_PREFERENCE && rule != RoutingRule.RESOURCE_OBJECTIVE) {
                throw new IllegalArgumentException("only optional rules can be skipped, got: " + rule);
            }
        }
        if (applied.size() + skipped.size() != RoutingRule.values().length
                || applied.stream().anyMatch(skipped::contains)) {
            throw new IllegalArgumentException("rulesApplied and rulesSkipped must partition every rule exactly once");
        }
    }

    private static void requireAscending(List<RoutingRule> rules, String name) {
        RoutingRule previous = null;
        for (RoutingRule rule : rules) {
            Objects.requireNonNull(rule, name + " must not contain null");
            if (previous != null && rule.compareTo(previous) <= 0) {
                throw new IllegalArgumentException(name + " must be unique and in rule order");
            }
            previous = rule;
        }
    }
}
