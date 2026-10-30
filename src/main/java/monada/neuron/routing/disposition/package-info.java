/**
 * Host dispositions of routing advice for the opt-in Forge routing extension (issue #98, ADR 0046).
 *
 * <p>An {@link monada.neuron.routing.disposition.AdviceDisposition} is the host's own record of what a person did with
 * one {@code AdviceReceipt} before anything ran: followed it, proposed another route, deferred, or declined. It holds
 * the advice identity only (the {@code DecisionRef}, the receipt digest, the kind of advice and the advised route when
 * there was one), never the receipt bytes or the decision, and it can neither reach {@code RoutingStateStore} nor feed
 * {@code RoutingFeedbackPolicy}: no type of the adaptation, feedback, outcome or review packages, nor of the resonance
 * package, is reachable from here.
 *
 * <p>Four terms stay apart in this extension, and a disposition is only the first:
 * <ul>
 *   <li><b>intent</b>: a disposition, what the host reports a person chose to do with the advice;</li>
 *   <li><b>authorization</b>: a host act at the side effect boundary, outside Neuron, which no disposition and no
 *       standing grants;</li>
 *   <li><b>invocation</b>: the worker run the host reports in {@code RoutingOutcome.invocation};</li>
 *   <li><b>acceptance</b>: the judged result, a {@code RoutingOutcome.outcomeStatus} of {@code ACCEPTED}.</li>
 * </ul>
 * Only the last two are evidence, and only through the feedback path; a disposition alone earns no credit. Dispositions
 * hold opaque host tokens inside their {@code DecisionRef} and route, so {@code toString} prints none of them and Neuron
 * never logs, persists or transports a disposition: the host decides whether and where to keep it.
 */
package monada.neuron.routing.disposition;
