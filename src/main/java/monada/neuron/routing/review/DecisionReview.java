package monada.neuron.routing.review;

import monada.neuron.routing.AbstainReason;
import monada.neuron.routing.Basis;
import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.Provenance;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.RoutingRule;
import monada.neuron.routing.StateMismatch;
import monada.neuron.routing.StateValidation;
import monada.neuron.routing.catalog.EligibilityReason;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteCatalog;
import monada.neuron.routing.catalog.RouteKey;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A structured view of one {@link RoutingDecision} for a person to read before accepting advice: exactly one of
 * {@link Selected}, {@link Abstain} or {@link NoEligibleRoute}, matching the decision's variant.
 *
 * <p>A review holds nothing but its decision. Exclusion groups, counts and {@link ReviewNotice} codes are derived from
 * it on every call, and the ranking is the decision's own, so a review can never rank again, drop an exclusion
 * reason or claim a fact the decision does not record. Equal decisions give equal reviews. Each call is linear in
 * the decision's bounded lists (at most {@value RouteCatalog#MAX_ROUTES} exclusions and
 * {@value RoutingDecision#MAX_CANDIDATES} candidates), with no I/O, shared state or threads.
 *
 * <p>A review is information only: it is never eligibility, readiness, authentication or authorization, and the
 * selected route stays advice that the host must recheck before execution.
 */
public sealed interface DecisionReview
        permits DecisionReview.Selected, DecisionReview.Abstain, DecisionReview.NoEligibleRoute {

    /**
     * Projects a decision into the review of the same variant.
     *
     * @param decision the decision to review; the review holds this same instance
     * @return the matching review
     * @throws NullPointerException if {@code decision} is null
     */
    static DecisionReview from(RoutingDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        return switch (decision) {
            case RoutingDecision.Selected selected -> new Selected(selected);
            case RoutingDecision.Abstain abstain -> new Abstain(abstain);
            case RoutingDecision.NoEligibleRoute none -> new NoEligibleRoute(none);
        };
    }

    /**
     * The reviewed decision, the same instance that was passed to {@link #from}. State, policy parameters and the
     * cohort binding are reachable only through it.
     *
     * @return the decision
     */
    RoutingDecision decision();

    /**
     * Identity of the reviewed decision.
     *
     * @return the provenance's decision reference
     */
    default DecisionRef decisionRef() {
        return decision().provenance().decisionRef();
    }

    /**
     * Catalog snapshot the decision evaluated.
     *
     * @return the provenance's catalog version
     */
    default String catalogVersion() {
        return decision().provenance().catalogVersion();
    }

    /**
     * Routing policy that decided.
     *
     * @return the provenance's policy id
     */
    default String policyId() {
        return decision().provenance().policyId();
    }

    /**
     * Version of the routing policy that decided.
     *
     * @return the provenance's policy version
     */
    default String policyVersion() {
        return decision().provenance().policyVersion();
    }

    /**
     * Decision contract the in memory decision records implement. It is the library constant, not a value read from
     * the decision.
     *
     * @return {@link RoutingDecision#CONTRACT_VERSION}
     */
    default String contractVersion() {
        return RoutingDecision.CONTRACT_VERSION;
    }

    /**
     * Groups the decision's exclusions by primary reason.
     *
     * @return one group per primary reason present, in {@link EligibilityReason} precedence order, no empty group;
     *     together the groups hold every exclusion exactly once; unmodifiable
     */
    default List<ExclusionGroup> exclusionGroups() {
        Map<EligibilityReason, List<EligibilityReport.Exclusion>> byPrimary = new EnumMap<>(EligibilityReason.class);
        for (EligibilityReport.Exclusion exclusion : decision().exclusions()) {
            byPrimary.computeIfAbsent(exclusion.primaryReason(), _ -> new ArrayList<>()).add(exclusion);
        }
        List<ExclusionGroup> groups = new ArrayList<>(byPrimary.size());
        byPrimary.forEach((reason, members) -> groups.add(new ExclusionGroup(reason, members)));
        return List.copyOf(groups);
    }

    /**
     * Counts the excluded routes that name a reason, as primary or additional.
     *
     * @param reason the reason to count
     * @return the number of exclusions naming {@code reason}, 0 to {@value RouteCatalog#MAX_ROUTES}
     * @throws NullPointerException if {@code reason} is null
     */
    default int excludedCount(EligibilityReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        int count = 0;
        for (EligibilityReport.Exclusion exclusion : decision().exclusions()) {
            if (exclusion.primaryReason() == reason || exclusion.additionalReasons().contains(reason)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Derives the notices from the decision, each from a field it records and nothing else.
     *
     * @return unique notices in {@link ReviewNotice} declaration order, always including
     *     {@link ReviewNotice#RECHECK_BEFORE_EXECUTION}; unmodifiable
     */
    default List<ReviewNotice> notices() {
        RoutingDecision decision = decision();
        Provenance provenance = decision.provenance();
        EnumSet<ReviewNotice> notices = EnumSet.of(ReviewNotice.RECHECK_BEFORE_EXECUTION);
        switch (decision) {
            case RoutingDecision.Selected selected -> {
                if (selected.overflowUsed()) {
                    notices.add(ReviewNotice.OVERFLOW_ROUTE_SELECTED);
                }
                if (selected.candidatesTruncated()) {
                    notices.add(ReviewNotice.RANKING_TRUNCATED);
                }
                // Only a selection records rulesSkipped; for the other variants these notices would be invented.
                if (selected.rulesSkipped().contains(RoutingRule.LEARNED_PREFERENCE)) {
                    notices.add(ReviewNotice.LEARNED_PREFERENCE_NOT_APPLIED);
                }
                if (provenance.parameters().objective().isPresent()
                        && selected.rulesSkipped().contains(RoutingRule.RESOURCE_OBJECTIVE)) {
                    notices.add(ReviewNotice.RESOURCE_OBJECTIVE_NOT_APPLIED);
                }
            }
            case RoutingDecision.Abstain abstain -> {
                if (abstain.candidatesTruncated()) {
                    notices.add(ReviewNotice.RANKING_TRUNCATED);
                }
            }
            case RoutingDecision.NoEligibleRoute _ -> {
            }
        }
        if (provenance.validation() instanceof StateValidation.Incompatible(var reasons)) {
            notices.add(ReviewNotice.STATE_INCOMPATIBLE);
            if (reasons.contains(StateMismatch.PROCESSED_CUTOFF_NEWER)) {
                notices.add(ReviewNotice.STATE_NEWER_THAN_CUTOFF);
            }
        }
        if (excludedCount(EligibilityReason.AVAILABILITY_UNKNOWN) > 0) {
            notices.add(ReviewNotice.AVAILABILITY_UNKNOWN);
        }
        if (excludedCount(EligibilityReason.REQUIRED_LIMIT_UNKNOWN) > 0) {
            notices.add(ReviewNotice.REQUIRED_LIMIT_UNKNOWN);
        }
        if (provenance.stageKind().isEmpty()) {
            notices.add(ReviewNotice.STAGE_KIND_NOT_RECORDED);
        }
        return List.copyOf(notices);
    }

    /**
     * Review of a {@link RoutingDecision.Selected}: one recommended route and the ranking that put it first.
     *
     * <p>A review is information only: it is never eligibility, readiness, authentication or authorization, and the
     * selected route stays advice that the host must recheck before execution.
     *
     * @param decision the reviewed selection
     */
    record Selected(RoutingDecision.Selected decision) implements DecisionReview {

        /** Requires a decision. */
        public Selected {
            Objects.requireNonNull(decision, "decision must not be null");
        }

        /**
         * The recommended route, which stays advice.
         *
         * @return the decision's route
         */
        public RouteKey route() {
            return decision.route();
        }

        /**
         * The permitted mode the eligibility filter chose.
         *
         * @return the decision's execution mode
         */
        public String executionMode() {
            return decision.executionMode();
        }

        /**
         * What separated the winner from the runner up.
         *
         * @return the decision's basis
         */
        public Basis basis() {
            return decision.basis();
        }

        /**
         * Whether the route is an overflow route the host permitted.
         *
         * @return the decision's overflow flag
         */
        public boolean overflowUsed() {
            return decision.overflowUsed();
        }

        /**
         * The decision's own ranking: never reordered, padded or extended.
         *
         * @return the decision's candidates, the first being {@link #route()}; unmodifiable
         */
        public List<RankedCandidate> ranking() {
            return decision.candidates();
        }

        /**
         * Whether more eligible routes exist than {@link #ranking()} lists.
         *
         * @return the decision's truncation flag
         */
        public boolean rankingTruncated() {
            return decision.candidatesTruncated();
        }

        /**
         * The ordering rule that separated the winner from the runner up.
         *
         * @return the winner's placement rule; empty when it was the only eligible route
         */
        public Optional<RoutingRule> decisiveRule() {
            return decision.candidates().getFirst().placement().rule();
        }
    }

    /**
     * Review of a {@link RoutingDecision.Abstain}: at least one route is eligible, but the policy declined to advise.
     *
     * <p>It has no decisive rule on purpose: an abstention decided nothing, and any placements stay visible in
     * {@link #ranking()}.
     *
     * <p>A review is information only: it is never eligibility, readiness, authentication or authorization, and the
     * selected route stays advice that the host must recheck before execution.
     *
     * @param decision the reviewed abstention
     */
    record Abstain(RoutingDecision.Abstain decision) implements DecisionReview {

        /** Requires a decision. */
        public Abstain {
            Objects.requireNonNull(decision, "decision must not be null");
        }

        /**
         * Why the policy declined to advise.
         *
         * @return the decision's reason
         */
        public AbstainReason reason() {
            return decision.reason();
        }

        /**
         * Every eligible route. This list is unranked; only {@link #ranking()} carries order.
         *
         * @return the decision's eligible routes in canonical order; unmodifiable
         */
        public List<RouteKey> eligible() {
            return decision.eligible();
        }

        /**
         * The decision's own ranking: empty for {@link AbstainReason#STATE_INCOMPATIBLE}, where ordering never ran.
         *
         * @return the decision's candidates; unmodifiable
         */
        public List<RankedCandidate> ranking() {
            return decision.candidates();
        }

        /**
         * Whether more eligible routes exist than {@link #ranking()} lists.
         *
         * @return the decision's truncation flag
         */
        public boolean rankingTruncated() {
            return decision.candidatesTruncated();
        }

        /**
         * Why the preference snapshot failed admission.
         *
         * @return the incompatible validation's reasons in {@link StateMismatch} declaration order, or an empty list
         *     for a compatible state; unmodifiable
         */
        public List<StateMismatch> stateMismatches() {
            return decision.provenance().validation() instanceof StateValidation.Incompatible(var reasons)
                    ? reasons : List.of();
        }
    }

    /**
     * Review of a {@link RoutingDecision.NoEligibleRoute}: the hard constraints excluded every route, and
     * {@link #exclusionGroups()} says why.
     *
     * <p>A review is information only: it is never eligibility, readiness, authentication or authorization, and the
     * selected route stays advice that the host must recheck before execution.
     *
     * @param decision the reviewed decision
     */
    record NoEligibleRoute(RoutingDecision.NoEligibleRoute decision) implements DecisionReview {

        /** Requires a decision. */
        public NoEligibleRoute {
            Objects.requireNonNull(decision, "decision must not be null");
        }
    }
}
