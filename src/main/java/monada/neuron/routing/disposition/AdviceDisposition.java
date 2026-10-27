package monada.neuron.routing.disposition;

import monada.neuron.routing.DecisionRef;
import monada.neuron.routing.RankedCandidate;
import monada.neuron.routing.RoutingDecision;
import monada.neuron.routing.catalog.EligibilityReport;
import monada.neuron.routing.catalog.RouteKey;
import monada.neuron.routing.receipt.AdviceReceipt;

import java.util.List;
import java.util.Objects;

/**
 * What a host did with one piece of routing advice before anything ran: exactly one of {@link Followed},
 * {@link AlternativeProposed}, {@link Deferred} or {@link Declined}, bound from an {@link AdviceReceipt} and the
 * {@link DecisionRef} the host believes it is answering.
 *
 * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization and
 * not quality evidence, and it awards no credit.
 *
 * <p>Every factory checks the reference first ({@link DispositionRejection#REFERENCE_MISMATCH}), then the variant rule,
 * then the route rule, and returns a coded {@link DispositionResult} instead of throwing for a host mistake; only a
 * null argument throws. A factory binds any receipt whose {@link DecisionRef} matches: it cannot know whether the advice
 * is stale. Staleness is the host's own re check (readiness contract, Section 6), and a host must not record a
 * disposition for a decision it has already re requested. A disposition keeps the advice identity (ref, digest, kind,
 * advised route) and nothing else:
 * no bytes, no decision, no ordinal, no reason, no actor, no time. {@code toString} prints no token. The record types
 * are values: equal components give equal records, and a disposition built from {@code AdviceReceipt.of(d)} equals one
 * built from the decoded receipt of its bytes.
 */
public sealed interface AdviceDisposition
        permits AdviceDisposition.Followed, AdviceDisposition.AlternativeProposed, AdviceDisposition.Deferred,
                AdviceDisposition.Declined {

    /**
     * Version of the disposition contract these types implement. The constants of {@link AdviceKind},
     * {@link AlternativeStanding} and {@link DispositionRejection} and the record components are part of it; renaming
     * one needs a successor version.
     */
    String CONTRACT_VERSION = "routing-advice-disposition/1";

    /** The identity of the advice this disposition answers. */
    AdviceReference advice();

    /**
     * Whether this disposition is the one the matching factory binds for {@code receipt}, with
     * {@code advice().decisionRef()} as the expected reference. That re derives the kind and the advised route from the
     * receipt, so a hand built reference that lies about its kind answers {@code false}, as does a receipt of a
     * re request or of the same ref decided over another catalog version, and a hand built alternative with a false
     * standing. One reference copy plus, for an alternative, at most {@code RouteCatalog.MAX_ROUTES} key compares.
     *
     * @throws NullPointerException if {@code receipt} is null
     */
    default boolean refersTo(AdviceReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        DecisionRef expected = advice().decisionRef();
        DispositionResult rerun = switch (this) {
            case Followed ignored -> followed(receipt, expected);
            case AlternativeProposed proposed -> alternative(receipt, expected, proposed.route());
            case Deferred ignored -> deferred(receipt, expected);
            case Declined ignored -> declined(receipt, expected);
        };
        return rerun instanceof DispositionResult.Bound(AdviceDisposition disposition) && equals(disposition);
    }

    /**
     * Binds a {@link Followed} when the receipt matches {@code expectedRef} and holds a selection. Named factory over
     * the receipt, so class-level.
     *
     * @return {@code Bound(Followed)}, or {@code Rejected} with {@link DispositionRejection#REFERENCE_MISMATCH} or
     *         {@link DispositionRejection#ADVICE_NOT_SELECTED}
     * @throws NullPointerException if an argument is null
     */
    static DispositionResult followed(AdviceReceipt receipt, DecisionRef expectedRef) {
        requireArguments(receipt, expectedRef);
        if (!refMatches(receipt, expectedRef)) {
            return new DispositionResult.Rejected(DispositionRejection.REFERENCE_MISMATCH);
        }
        AdviceReference advice = AdviceReference.of(receipt);
        if (advice.kind() != AdviceKind.SELECTED) {
            return new DispositionResult.Rejected(DispositionRejection.ADVICE_NOT_SELECTED);
        }
        return new DispositionResult.Bound(new Followed(advice));
    }

    /**
     * Binds an {@link AlternativeProposed} for a receipt of any variant that matches {@code expectedRef}, with the
     * route's {@link AlternativeStanding} derived from the decision alone: a ranked candidate of a selection or an
     * eligible route of an abstention is {@code ELIGIBLE_IN_DECISION}, an exclusion is {@code EXCLUDED_IN_DECISION},
     * anything else is {@code HOST_CLAIM}. A key that shares the advised route id under another version is a different
     * route and so an alternative. Named factory over the receipt, so class-level. Linear in the decision's lists, at
     * most {@code RouteCatalog.MAX_ROUTES} key compares, no collection allocated.
     *
     * @return {@code Bound(AlternativeProposed)}, or {@code Rejected} with
     *         {@link DispositionRejection#REFERENCE_MISMATCH} or {@link DispositionRejection#ALTERNATIVE_EQUALS_ADVICE}
     * @throws NullPointerException if an argument is null
     */
    static DispositionResult alternative(AdviceReceipt receipt, DecisionRef expectedRef, RouteKey route) {
        requireArguments(receipt, expectedRef);
        Objects.requireNonNull(route, "route must not be null");
        if (!refMatches(receipt, expectedRef)) {
            return new DispositionResult.Rejected(DispositionRejection.REFERENCE_MISMATCH);
        }
        AdviceReference advice = AdviceReference.of(receipt);
        if (advice.advisedRoute().isPresent() && advice.advisedRoute().get().equals(route)) {
            return new DispositionResult.Rejected(DispositionRejection.ALTERNATIVE_EQUALS_ADVICE);
        }
        return new DispositionResult.Bound(new AlternativeProposed(advice, route, standing(receipt.decision(), route)));
    }

    /**
     * Binds a {@link Deferred} for a receipt of any variant that matches {@code expectedRef}. Named factory over the
     * receipt, so class-level.
     *
     * @return {@code Bound(Deferred)}, or {@code Rejected} with {@link DispositionRejection#REFERENCE_MISMATCH}
     * @throws NullPointerException if an argument is null
     */
    static DispositionResult deferred(AdviceReceipt receipt, DecisionRef expectedRef) {
        requireArguments(receipt, expectedRef);
        if (!refMatches(receipt, expectedRef)) {
            return new DispositionResult.Rejected(DispositionRejection.REFERENCE_MISMATCH);
        }
        return new DispositionResult.Bound(new Deferred(AdviceReference.of(receipt)));
    }

    /**
     * Binds a {@link Declined} for a receipt of any variant that matches {@code expectedRef}. Named factory over the
     * receipt, so class-level.
     *
     * @return {@code Bound(Declined)}, or {@code Rejected} with {@link DispositionRejection#REFERENCE_MISMATCH}
     * @throws NullPointerException if an argument is null
     */
    static DispositionResult declined(AdviceReceipt receipt, DecisionRef expectedRef) {
        requireArguments(receipt, expectedRef);
        if (!refMatches(receipt, expectedRef)) {
            return new DispositionResult.Rejected(DispositionRejection.REFERENCE_MISMATCH);
        }
        return new DispositionResult.Bound(new Declined(AdviceReference.of(receipt)));
    }

    private static void requireArguments(AdviceReceipt receipt, DecisionRef expectedRef) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        Objects.requireNonNull(expectedRef, "expectedRef must not be null");
    }

    private static boolean refMatches(AdviceReceipt receipt, DecisionRef expectedRef) {
        return receipt.decision().provenance().decisionRef().equals(expectedRef);
    }

    /**
     * Eligible routes first, then exclusions, by indexed loops: the two lists are disjoint by construction and both
     * are capped by the decision constructors, so a set would cost more than it saves. Under
     * {@code POLICY_TRADEOFF_UNRESOLVED} the candidates are a subset of {@code eligible()}, so one scan covers both.
     */
    private static AlternativeStanding standing(RoutingDecision decision, RouteKey route) {
        switch (decision) {
            case RoutingDecision.Selected selected -> {
                List<RankedCandidate> candidates = selected.candidates();
                for (int i = 0; i < candidates.size(); i++) {
                    if (candidates.get(i).key().equals(route)) {
                        return AlternativeStanding.ELIGIBLE_IN_DECISION;
                    }
                }
            }
            case RoutingDecision.Abstain abstain -> {
                List<RouteKey> eligible = abstain.eligible();
                for (int i = 0; i < eligible.size(); i++) {
                    if (eligible.get(i).equals(route)) {
                        return AlternativeStanding.ELIGIBLE_IN_DECISION;
                    }
                }
            }
            case RoutingDecision.NoEligibleRoute ignored -> {
                // Nothing was eligible, so only the exclusions can name the route.
            }
        }
        List<EligibilityReport.Exclusion> exclusions = decision.exclusions();
        for (int i = 0; i < exclusions.size(); i++) {
            if (exclusions.get(i).key().equals(route)) {
                return AlternativeStanding.EXCLUDED_IN_DECISION;
            }
        }
        return AlternativeStanding.HOST_CLAIM;
    }

    /**
     * The person took the advised route. Exists only for {@link AdviceKind#SELECTED} advice: the constructor rejects
     * any other kind, so an abstention cannot be marked followed even by hand.
     *
     * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization
     * and not quality evidence, and it awards no credit.
     *
     * @param advice the identity of the selection
     */
    record Followed(AdviceReference advice) implements AdviceDisposition {

        /** Requires a reference to a selection. */
        public Followed {
            Objects.requireNonNull(advice, "advice must not be null");
            if (advice.kind() != AdviceKind.SELECTED) {
                throw new IllegalArgumentException("only SELECTED advice can be followed, got " + advice.kind());
            }
        }

        @Override
        public String toString() {
            return "Followed[" + advice.fields() + "]";
        }
    }

    /**
     * The person named another route instead of the advice, for any variant. The route always differs from the advised
     * route when there is one (the constructor rejects equality), so divergence is readable from the record alone, and
     * the standing says what the decision proves about the route. A route with any standing is a request to the host's
     * own checks at the side effect boundary, never a route grant.
     *
     * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization
     * and not quality evidence, and it awards no credit.
     *
     * @param advice the identity of the advice
     * @param route the route the person proposed; never printed, since it holds a route id
     * @param standing what the decision proves about {@code route}
     */
    record AlternativeProposed(AdviceReference advice, RouteKey route, AlternativeStanding standing)
            implements AdviceDisposition {

        /** Requires every component and a route other than the advised one. */
        public AlternativeProposed {
            Objects.requireNonNull(advice, "advice must not be null");
            Objects.requireNonNull(route, "route must not be null");
            Objects.requireNonNull(standing, "standing must not be null");
            if (advice.advisedRoute().isPresent() && advice.advisedRoute().get().equals(route)) {
                throw new IllegalArgumentException("an alternative must differ from the advised route");
            }
        }

        @Override
        public String toString() {
            return "AlternativeProposed[" + advice.fields() + ", standing=" + standing + "]";
        }
    }

    /**
     * The person put the advice aside without a route. Any variant can be deferred.
     *
     * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization
     * and not quality evidence, and it awards no credit.
     *
     * @param advice the identity of the advice
     */
    record Deferred(AdviceReference advice) implements AdviceDisposition {

        /** Requires a reference. */
        public Deferred {
            Objects.requireNonNull(advice, "advice must not be null");
        }

        @Override
        public String toString() {
            return "Deferred[" + advice.fields() + "]";
        }
    }

    /**
     * The person turned the advice down without a route. Any variant can be declined.
     *
     * <p>A disposition records what a host did with advice before anything ran: it is not execution, not authorization
     * and not quality evidence, and it awards no credit.
     *
     * @param advice the identity of the advice
     */
    record Declined(AdviceReference advice) implements AdviceDisposition {

        /** Requires a reference. */
        public Declined {
            Objects.requireNonNull(advice, "advice must not be null");
        }

        @Override
        public String toString() {
            return "Declined[" + advice.fields() + "]";
        }
    }
}
