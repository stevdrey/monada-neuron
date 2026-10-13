/**
 * Immutable, bounded records for host-evaluated routing outcomes and a deterministic learning-eligibility check
 * for the opt-in Forge routing extension (Forge Routing Contract v1, issue #63).
 *
 * <p>Forge supplies every judgment; Neuron never inspects code, calls a judge, computes billing or generates an
 * identifier. A completed worker invocation is not validated acceptance, and an assessment is not a
 * {@code RoutingFeedback}: it only states whether an observation can support one.
 */
package monada.neuron.routing.outcome;
