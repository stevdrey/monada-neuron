/**
 * Deterministic, explainable worker-route recommendation for one host-defined workflow stage, with abstention
 * (Forge Routing Contract v1, issue #62).
 *
 * <p>{@link monada.neuron.routing.RoutingPolicy#decide} is a pure function of a request, a catalog snapshot and an
 * immutable preference snapshot; it reserves nothing, executes nothing and is never an
 * {@code ActionStatus}. {@link monada.neuron.routing.RoutingReasoningStage} is the optional, opt-in cycle adapter.
 * Route identity travels in typed records, never in {@code Signal}.
 */
package monada.neuron.routing;
