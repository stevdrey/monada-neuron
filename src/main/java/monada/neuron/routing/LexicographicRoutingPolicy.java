package monada.neuron.routing;

import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteEligibilityFilter;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.catalog.RoutingRequest;

import java.util.ArrayList;
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
 * ({@code Abstain(POLICY_TRADEOFF_UNRESOLVED)}). Time {@code O(R*Q + R log R + R log C)} for {@code R <= 32} routes,
 * {@code Q <= 16} requirement tokens and {@code C <= 256} cohorts; extra space {@code O(R)}. Instances are immutable and stateless.
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
    private final LexicographicRanking ranking;
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
        this.ranking = new LexicographicRanking(minSupportingObservations, objective);
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
        String bucket = definition.cohortMapping().bucketOf(request.features());
        LexicographicRanking.Result ranked = ranking.rank(request, catalog, report.eligible(), preference, bucket);
        List<RankedCandidate> candidates = ranked.candidates();
        LexicographicRanking.Item best = ranked.order().getFirst();
        if (maxAutoSelectTier.isPresent() && best.tier() > maxAutoSelectTier.getAsInt()) {
            List<RouteKey> eligible = new ArrayList<>(report.eligible().size());
            report.eligible().forEach(route -> eligible.add(route.key()));
            return new RoutingDecision.Abstain(provenance, AbstainReason.POLICY_TRADEOFF_UNRESOLVED, eligible,
                    candidates, ranked.truncated(), report.excluded());
        }
        var cohort = new CohortBinding(bucket, definition.cohortMapping().bucketMappingVersion(),
                definition.mappingVersion(), request.features().schemaVersion());
        return new RoutingDecision.Selected(provenance, best.route().key(), best.route().executionMode(),
                candidates.getFirst().placement().basis(), best.route().overflow(), candidates, ranked.truncated(),
                ranked.applied(), ranked.skipped(), report.excluded(), cohort);
    }

    private Provenance provenance(
            RoutingRequest request, RouteCatalog catalog, RoutingPreference preference, StateValidation validation) {
        return new Provenance(DecisionRef.of(request), catalog.catalogVersion(), POLICY_ID, POLICY_VERSION,
                request.cutoff(), preference.binding(), validation,
                new PolicyParameters(minSupportingObservations, maxAutoSelectTier, objective,
                        definition.mappingVersion()));
    }
}
