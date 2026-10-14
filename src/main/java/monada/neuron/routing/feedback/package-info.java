/**
 * Bounded, explicit routing feedback for the opt-in Forge routing extension (Forge Routing Contract v1, issue #65).
 *
 * <p>{@code RoutingFeedback} is separate from {@code OutcomeFeedback}: it is derived from a learning-eligible
 * {@code RoutingObservation}, credits only the route that actually ran and only the Node the caller bound to that
 * route's cohort, and records which explicit rule produced the score. Derivation is pure: it reads no clock and no
 * randomness, mutates no Node and keeps no history. Applied-revision bookkeeping is caller-owned; this package only
 * defines its records and the pure functions that decide how a new revision relates to what was already applied.
 */
package monada.neuron.routing.feedback;
