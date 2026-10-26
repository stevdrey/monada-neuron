package monada.neuron.routing.review;

/**
 * A coded fact a host shows beside a {@link DecisionReview}, derived only from fields the decision records. Forge
 * owns the wording and its localization; the constants are the contract.
 *
 * <p>Declaration order is display order, not severity. {@link DecisionReview#notices()} lists each notice at most
 * once, in this order.
 *
 * <p>A review is information only: it is never eligibility, readiness, authentication or authorization, and the
 * selected route stays advice that the host must recheck before execution.
 */
public enum ReviewNotice {
    /** Present on every review: the host rechecks authorization, availability and quota before any execution. */
    RECHECK_BEFORE_EXECUTION,
    /** The selected route is an overflow route the host permitted. */
    OVERFLOW_ROUTE_SELECTED,
    /** More eligible routes exist than the ranking lists. */
    RANKING_TRUNCATED,
    /** The preference snapshot failed admission, so no ordering ran. */
    STATE_INCOMPATIBLE,
    /** The preference snapshot's watermark is newer than the request cutoff. */
    STATE_NEWER_THAN_CUTOFF,
    /** At least one route was excluded, in part, because its availability is unknown. */
    AVAILABILITY_UNKNOWN,
    /** At least one route was excluded, in part, because a required limit is unknown. */
    REQUIRED_LIMIT_UNKNOWN,
    /**
     * The selection skipped learned preference, so no learning shaped the order (expected at cold start).
     *
     * <p>The decision's {@code basis} names only what separated the winner, so this is the one signal that learned
     * preference took no part at all.
     */
    LEARNED_PREFERENCE_NOT_APPLIED,
    /**
     * The policy has a resource objective, but it refined no tied group of the selection.
     *
     * <p>That happens with unknown or incomparable estimates and also when no tie was left to refine (a sole route,
     * or tier and priority already separated every route). The decision does not record which, so a host must not
     * claim a cause. It also cannot show a tied group the objective left unrefined while it refined another.
     */
    RESOURCE_OBJECTIVE_NOT_APPLIED,
    /** The decision did not record its stage kind, so it cannot earn routing feedback. */
    STAGE_KIND_NOT_RECORDED
}
