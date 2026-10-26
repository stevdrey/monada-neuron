/**
 * Bounded, coded review summaries of a {@link monada.neuron.routing.RoutingDecision} for a person to read before a
 * host accepts advice (Forge Integration Readiness Contract v1, Section 5.5, issue #96).
 *
 * <p>{@link monada.neuron.routing.review.DecisionReview#from} is a projection: it holds the exact decision and
 * derives every view from it, so it never ranks again, never recomputes eligibility and grants nothing. Codes, not
 * English sentences, carry the meaning; the host owns wording and localization.
 *
 * <p>A host handles every outcome with an exhaustive {@code switch}, so an abstention can never pass for advice:
 *
 * <pre>{@code
 * DecisionReview review = DecisionReview.from(policy.decide(request, catalog, preference));
 * String headline = switch (review) {
 *     case DecisionReview.Selected selected -> "advice: " + selected.route().routeId() + " via "
 *             + selected.decisiveRule().map(Enum::name).orElse("only eligible route");
 *     case DecisionReview.Abstain abstain -> "no advice: " + abstain.reason();
 *     case DecisionReview.NoEligibleRoute none -> "no eligible route, " + none.exclusionGroups().size() + " reasons";
 * };
 * // Every review lists RECHECK_BEFORE_EXECUTION: a selection is still advice, never permission.
 * List<ReviewNotice> notices = review.notices();
 * }</pre>
 */
package monada.neuron.routing.review;
