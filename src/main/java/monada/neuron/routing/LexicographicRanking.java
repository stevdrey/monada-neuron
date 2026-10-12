package monada.neuron.routing;

import monada.neuron.routing.catalog.CatalogEntry;
import monada.neuron.routing.catalog.EligibilityReport.EligibleRoute;
import monada.neuron.routing.catalog.ResourceEstimate;
import monada.neuron.routing.catalog.ResourceValue;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The fixed lexicographic ordering of Forge Routing Contract v1, section 3.3, over the eligible routes: tier, host
 * fallback priority, learned preference, resource objective, canonical route order.
 *
 * <p>It is the single definition of the ordering: {@link LexicographicRoutingPolicy} decides with it and
 * {@link LexicographicDecisionVerifier} recomputes it from a decision's recorded parameters to verify the decision.
 * Learned preference is applied only when every eligible route has a cohort with at least
 * {@code minSupportingObservations}; the resource objective is applied to each group still tied after the first three
 * rules and only if every route of that group has a {@code Known} estimate of the objective's dimension with one unit.
 * Instances are immutable and stateless.
 */
final class LexicographicRanking {

    private final int minSupportingObservations;
    private final Optional<ResourceObjective> objective;

    LexicographicRanking(int minSupportingObservations, Optional<ResourceObjective> objective) {
        if (minSupportingObservations < 1) {
            throw new IllegalArgumentException(
                    "minSupportingObservations must be at least 1, got: " + minSupportingObservations);
        }
        this.minSupportingObservations = minSupportingObservations;
        this.objective = Objects.requireNonNull(objective, "objective must not be null");
    }

    /**
     * One ranked eligible route.
     *
     * @param route the eligible route (execution mode and overflow flag come from the filter)
     * @param tier the route's catalog tier
     */
    record Item(EligibleRoute route, int tier) {
    }

    /**
     * The complete ranking.
     *
     * @param order every eligible route, best first
     * @param candidates the bounded explanation: the first {@value RoutingDecision#MAX_CANDIDATES} routes with the
     *         rule that separates each from its neighbour, computed once
     * @param learnedActive whether the learned preference rule applied
     * @param resourceApplied whether the resource objective refined at least one tied group
     */
    record Result(List<Item> order, List<RankedCandidate> candidates, boolean learnedActive,
            boolean resourceApplied) {

        /** Whether more routes are eligible than {@link #candidates()} lists. */
        boolean truncated() {
            return order.size() > RoutingDecision.MAX_CANDIDATES;
        }

        /** The rules that took part in the ordering, in rule order. */
        List<RoutingRule> applied() {
            var rules = new ArrayList<RoutingRule>(5);
            rules.add(RoutingRule.TIER);
            rules.add(RoutingRule.FALLBACK_PRIORITY);
            if (learnedActive) {
                rules.add(RoutingRule.LEARNED_PREFERENCE);
            }
            if (resourceApplied) {
                rules.add(RoutingRule.RESOURCE_OBJECTIVE);
            }
            rules.add(RoutingRule.ROUTE_ORDER);
            return rules;
        }

        /** The optional rules that were skipped, in rule order. */
        List<RoutingRule> skipped() {
            var rules = new ArrayList<RoutingRule>(2);
            if (!learnedActive) {
                rules.add(RoutingRule.LEARNED_PREFERENCE);
            }
            if (!resourceApplied) {
                rules.add(RoutingRule.RESOURCE_OBJECTIVE);
            }
            return rules;
        }
    }

    /** One eligible candidate with the values the ordering rules read. */
    private static final class Work {
        final EligibleRoute route;
        final int tier;
        final int fallbackPriority;
        double preference;
        boolean resourceApplied;
        long resourceValue;

        Work(EligibleRoute route, int tier, int fallbackPriority) {
            this.route = route;
            this.tier = tier;
            this.fallbackPriority = fallbackPriority;
        }
    }

    /**
     * Ranks the eligible routes.
     *
     * @param request the request (supplies the stage kind for cohort lookups)
     * @param catalog the catalog snapshot (tiers, priorities and estimates)
     * @param eligible the eligible routes in canonical order, at least one
     * @param preference the preference snapshot, assumed compatible with the request
     * @param bucket the request's cohort bucket, or {@code null} when none applies (learned preference then inactive)
     */
    Result rank(RoutingRequest request, RouteCatalog catalog, List<EligibleRoute> eligible,
            RoutingPreference preference, String bucket) {
        // Both lists are in canonical route order, so one merge pass pairs each eligible route with its entry.
        List<CatalogEntry> entries = catalog.entries();
        List<Work> work = new ArrayList<>(eligible.size());
        int next = 0;
        for (EligibleRoute route : eligible) {
            while (!entries.get(next).key().equals(route.key())) {
                next++;
            }
            CatalogEntry entry = entries.get(next);
            work.add(new Work(route, entry.descriptor().tier(), entry.fallbackPriority()));
        }
        boolean learnedActive = applyPreference(request, preference, bucket, work);
        Comparator<Work> byRules = Comparator.<Work>comparingInt(w -> w.tier)
                .thenComparingInt(w -> w.fallbackPriority);
        if (learnedActive) {
            byRules = byRules.thenComparing((a, b) -> Double.compare(b.preference, a.preference));
        }
        Comparator<Work> grouping = byRules;
        work.sort(byRules.thenComparing(w -> w.route.key()));
        boolean resourceApplied = objective.isPresent() && refine(catalog, work, grouping);
        List<Item> order = new ArrayList<>(work.size());
        for (Work w : work) {
            order.add(new Item(w.route, w.tier));
        }
        // Placements are needed only for the bounded explanation, not for every eligible route.
        int shown = Math.min(work.size(), RoutingDecision.MAX_CANDIDATES);
        List<RankedCandidate> candidates = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            candidates.add(new RankedCandidate(i, work.get(i).route.key(), placement(work, i, learnedActive)));
        }
        return new Result(List.copyOf(order), List.copyOf(candidates), learnedActive, resourceApplied);
    }

    /** Looks up each candidate's cohort by binary search in the snapshot; active only if every one has enough support. */
    private boolean applyPreference(
            RoutingRequest request, RoutingPreference preference, String bucket, List<Work> work) {
        if (bucket == null || preference.cohorts().isEmpty()) {
            return false;
        }
        boolean active = true;
        for (Work w : work) {
            CohortPreference cohort = preference.find(request.stageKind(), bucket, w.route.key());
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

    private boolean comparable(List<Work> work, int start, int end, HashMap<RouteKey, ResourceEstimate> estimates) {
        String unit = null;
        for (int i = start; i < end; i++) {
            ResourceEstimate estimate = estimates.get(work.get(i).route.key());
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
            w.resourceValue = ((ResourceValue.Known) estimates.get(w.route.key()).value()).value();
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
