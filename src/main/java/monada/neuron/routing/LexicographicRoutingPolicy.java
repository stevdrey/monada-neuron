package monada.neuron.routing;

import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.ResourceEstimate;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteEligibilityFilter;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reference policy {@code route-lex} v1: a small lexicographic rule list over the <em>eligible</em> routes, with no
 * blended scalar (contract section 3.3).
 *
 * <ol>
 *   <li>host {@code tier}, ascending;
 *   <li>host {@code fallbackPriority}, ascending;
 *   <li>learned preference, descending, applied only when the snapshot is compatible and <em>every</em> eligible
 *       candidate has at least {@code minSupportingObservations} for its cohort; otherwise skipped for all;
 *   <li>the {@link ResourceObjective}, applied to each group still tied after rules 1 to 3, and only when every
 *       route of that group has a {@code Known} estimate of the objective's dimension with the same unit; unknown
 *       and not-measured values are never cheapest, fastest or zero;
 *   <li>canonical route order ({@code RouteId} by code point, then {@code RouteVersion} numerically), the total
 *       final tie-breaker.
 * </ol>
 *
 * <p>Eligibility is final and evaluated first; preference only reorders eligible routes. Steps: eligibility, then
 * state admission ({@code Abstain(STATE_INCOMPATIBLE)}), then ordering, then the optional tier check
 * ({@code Abstain(POLICY_TRADEOFF_UNRESOLVED)}). Time {@code O(R*Q + R log R)} for {@code R <= 32} routes and
 * {@code Q <= 16} requirement tokens; extra space {@code O(R)}. Instances are immutable and stateless.
 */
public final class LexicographicRoutingPolicy implements RoutingPolicy {

    /** Policy id. */
    public static final String POLICY_ID = "route-lex";

    /** Policy version. */
    public static final String POLICY_VERSION = "1";

    /** Default minimum supporting observations per cohort. */
    public static final int DEFAULT_MIN_SUPPORTING_OBSERVATIONS = 3;

    private static final RouteEligibilityFilter FILTER = new RouteEligibilityFilter();

    private final RoutingStateDefinition definition;
    private final int minSupportingObservations;
    private final OptionalInt maxAutoSelectTier;
    private final Optional<ResourceObjective> objective;

    /**
     * Creates a policy.
     *
     * @param definition state definition (cohort mapping and mapping version) the policy uses
     * @param minSupportingObservations observations a cohort needs before preference counts, at least 1
     * @param maxAutoSelectTier highest tier advised without host confirmation; empty means unbounded
     * @param objective optional resource objective
     */
    public LexicographicRoutingPolicy(
            RoutingStateDefinition definition,
            int minSupportingObservations,
            OptionalInt maxAutoSelectTier,
            Optional<ResourceObjective> objective) {
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        if (minSupportingObservations < 1) {
            throw new IllegalArgumentException(
                    "minSupportingObservations must be at least 1, got: " + minSupportingObservations);
        }
        this.minSupportingObservations = minSupportingObservations;
        this.maxAutoSelectTier = Objects.requireNonNull(maxAutoSelectTier, "maxAutoSelectTier must not be null");
        if (maxAutoSelectTier.isPresent() && maxAutoSelectTier.getAsInt() < 1) {
            throw new IllegalArgumentException(
                    "maxAutoSelectTier must be at least 1, got: " + maxAutoSelectTier.getAsInt());
        }
        this.objective = Objects.requireNonNull(objective, "objective must not be null");
    }

    /** The reference configuration: default state definition, 3 supporting observations, no tier cap, no objective. */
    public static LexicographicRoutingPolicy reference() {
        return new LexicographicRoutingPolicy(RoutingStateDefinition.reference(),
                DEFAULT_MIN_SUPPORTING_OBSERVATIONS, OptionalInt.empty(), Optional.empty());
    }

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public String policyVersion() {
        return POLICY_VERSION;
    }

    /** The state definition this policy admits snapshots against. */
    public RoutingStateDefinition definition() {
        return definition;
    }

    @Override
    public RoutingDecision decide(RoutingRequest request, RouteCatalog catalog, RoutingPreference preference) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(preference, "preference must not be null");
        EligibilityReport report = FILTER.evaluate(request, catalog);
        if (report.noneEligible()) {
            return new RoutingDecision.NoEligibleRoute(
                    provenance(request, catalog, preference, new StateValidation.NotEvaluated()), report.excluded());
        }
        List<StateMismatch> mismatches = preference.mismatches(request, definition);
        if (!mismatches.isEmpty()) {
            List<RouteKey> eligible = new ArrayList<>(report.eligible().size());
            report.eligible().forEach(route -> eligible.add(route.key()));
            return new RoutingDecision.Abstain(
                    provenance(request, catalog, preference, new StateValidation.Incompatible(mismatches)),
                    AbstainReason.STATE_INCOMPATIBLE, eligible, List.of(), false, report.excluded());
        }
        Provenance provenance = provenance(request, catalog, preference, new StateValidation.Compatible());
        Ranking ranking = rank(request, catalog, report, preference);
        List<Work> order = ranking.order;
        int shown = Math.min(order.size(), RoutingDecision.MAX_CANDIDATES);
        List<RankedCandidate> candidates = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            candidates.add(new RankedCandidate(i, order.get(i).key, placement(order, i, ranking.learnedActive)));
        }
        boolean truncated = order.size() > shown;
        Work best = order.getFirst();
        if (maxAutoSelectTier.isPresent() && best.tier > maxAutoSelectTier.getAsInt()) {
            List<RouteKey> eligible = new ArrayList<>(report.eligible().size());
            report.eligible().forEach(route -> eligible.add(route.key()));
            return new RoutingDecision.Abstain(provenance, AbstainReason.POLICY_TRADEOFF_UNRESOLVED, eligible,
                    candidates, truncated, report.excluded());
        }
        Basis basis = switch (candidates.getFirst().placement()) {
            case TIER, FALLBACK_PRIORITY -> Basis.HOST_PRIORITY;
            case LEARNED_PREFERENCE -> Basis.LEARNED_PREFERENCE;
            case RESOURCE_OBJECTIVE -> Basis.RESOURCE_OBJECTIVE;
            case ONLY_ELIGIBLE, ROUTE_ORDER -> Basis.COLD_START;
        };
        var applied = new ArrayList<RoutingRule>(5);
        var skipped = new ArrayList<RoutingRule>(2);
        applied.add(RoutingRule.TIER);
        applied.add(RoutingRule.FALLBACK_PRIORITY);
        (ranking.learnedActive ? applied : skipped).add(RoutingRule.LEARNED_PREFERENCE);
        (ranking.resourceApplied ? applied : skipped).add(RoutingRule.RESOURCE_OBJECTIVE);
        applied.add(RoutingRule.ROUTE_ORDER);
        var cohort = new CohortBinding(definition.cohortMapping().bucketOf(request.features()),
                definition.cohortMapping().bucketMappingVersion(), definition.mappingVersion(),
                request.features().schemaVersion());
        return new RoutingDecision.Selected(provenance, best.key, best.executionMode, basis, best.overflow,
                candidates, truncated, applied, skipped, report.excluded(), cohort);
    }

    private Provenance provenance(
            RoutingRequest request, RouteCatalog catalog, RoutingPreference preference, StateValidation validation) {
        return new Provenance(DecisionRef.of(request), catalog.catalogVersion(), POLICY_ID, POLICY_VERSION,
                request.cutoff(), preference.binding(), validation);
    }

    /** One eligible candidate with the values the ordering rules read. */
    private static final class Work {
        final RouteKey key;
        final String executionMode;
        final boolean overflow;
        final int tier;
        final int fallbackPriority;
        double preference;
        boolean resourceApplied;
        long resourceValue;

        Work(RouteKey key, String executionMode, boolean overflow, int tier, int fallbackPriority) {
            this.key = key;
            this.executionMode = executionMode;
            this.overflow = overflow;
            this.tier = tier;
            this.fallbackPriority = fallbackPriority;
        }
    }

    private record Ranking(List<Work> order, boolean learnedActive, boolean resourceApplied) {
    }

    private Ranking rank(
            RoutingRequest request, RouteCatalog catalog, EligibilityReport report, RoutingPreference preference) {
        // Both lists are in canonical route order, so one merge pass pairs each eligible route with its entry.
        List<CatalogEntry> entries = catalog.entries();
        List<Work> work = new ArrayList<>(report.eligible().size());
        int next = 0;
        for (EligibilityReport.EligibleRoute route : report.eligible()) {
            while (!entries.get(next).key().equals(route.key())) {
                next++;
            }
            CatalogEntry entry = entries.get(next);
            work.add(new Work(route.key(), route.executionMode(), route.overflow(),
                    entry.descriptor().tier(), entry.fallbackPriority()));
        }
        boolean learnedActive = applyPreference(request, preference, work);
        Comparator<Work> byRules = Comparator.<Work>comparingInt(w -> w.tier)
                .thenComparingInt(w -> w.fallbackPriority);
        if (learnedActive) {
            byRules = byRules.thenComparing((a, b) -> Double.compare(b.preference, a.preference));
        }
        Comparator<Work> grouping = byRules;
        work.sort(byRules.thenComparing(w -> w.key));
        boolean resourceApplied = objective.isPresent() && refine(catalog, work, grouping);
        return new Ranking(work, learnedActive, resourceApplied);
    }

    /** Looks up each candidate's cohort; the rule is active only if every candidate has enough support. */
    private boolean applyPreference(RoutingRequest request, RoutingPreference preference, List<Work> work) {
        if (preference.cohorts().isEmpty()) {
            return false;
        }
        String bucket = definition.cohortMapping().bucketOf(request.features());
        var cohorts = new HashMap<RouteKey, CohortPreference>();
        for (CohortPreference cohort : preference.cohorts()) {
            if (cohort.stageKind().equals(request.stageKind()) && cohort.cohortBucket().equals(bucket)) {
                cohorts.put(cohort.route(), cohort);
            }
        }
        boolean active = true;
        for (Work w : work) {
            CohortPreference cohort = cohorts.get(w.key);
            if (cohort == null || cohort.supportingObservations() < minSupportingObservations) {
                active = false;
            } else {
                w.preference = cohort.preferenceValue();
            }
        }
        return active;
    }

    /** Applies the resource objective inside each tied group; returns whether any group was refined. */
    private boolean refine(RouteCatalog catalog, List<Work> work, Comparator<Work> grouping) {
        ResourceObjective goal = objective.get();
        var estimates = new HashMap<RouteKey, ResourceEstimate>();
        for (ResourceEstimate estimate : catalog.estimates()) {
            if (estimate.dimension().equals(goal.dimension())) {
                estimates.put(estimate.key(), estimate);
            }
        }
        boolean any = false;
        int start = 0;
        while (start < work.size()) {
            int end = start + 1;
            while (end < work.size() && grouping.compare(work.get(start), work.get(end)) == 0) {
                end++;
            }
            if (end - start > 1 && comparable(work, start, end, estimates)) {
                List<Work> group = work.subList(start, end);
                Comparator<Work> byValue = Comparator.comparingLong(w -> w.resourceValue);
                group.sort(goal.direction() == ResourceObjective.Direction.MINIMIZE
                        ? byValue : byValue.reversed());
                any = true;
            }
            start = end;
        }
        return any;
    }

    private boolean comparable(
            List<Work> work, int start, int end, HashMap<RouteKey, ResourceEstimate> estimates) {
        String unit = null;
        for (int i = start; i < end; i++) {
            ResourceEstimate estimate = estimates.get(work.get(i).key);
            if (estimate == null || !(estimate.value() instanceof ResourceValue.Known)) {
                return false;
            }
            if (unit == null) {
                unit = estimate.unit();
            } else if (!unit.equals(estimate.unit())) {
                return false;
            }
        }
        for (int i = start; i < end; i++) {
            Work w = work.get(i);
            w.resourceApplied = true;
            w.resourceValue = ((ResourceValue.Known) estimates.get(w.key).value()).value();
        }
        return true;
    }

    private Placement placement(List<Work> order, int index, boolean learnedActive) {
        if (order.size() == 1) {
            return Placement.ONLY_ELIGIBLE;
        }
        Work a = index == 0 ? order.get(0) : order.get(index - 1);
        Work b = index == 0 ? order.get(1) : order.get(index);
        if (a.tier != b.tier) {
            return Placement.TIER;
        }
        if (a.fallbackPriority != b.fallbackPriority) {
            return Placement.FALLBACK_PRIORITY;
        }
        if (learnedActive && Double.compare(a.preference, b.preference) != 0) {
            return Placement.LEARNED_PREFERENCE;
        }
        if (a.resourceApplied && b.resourceApplied && a.resourceValue != b.resourceValue) {
            return Placement.RESOURCE_OBJECTIVE;
        }
        return Placement.ROUTE_ORDER;
    }
}
