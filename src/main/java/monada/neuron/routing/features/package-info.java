/**
 * Bounded, host-approved task and stage characteristics for the opt-in Forge routing extension, and a
 * versioned deterministic encoder from those characteristics to {@code OBSERVATION} signals.
 *
 * <p>The typed {@link monada.neuron.routing.features.TaskFeatures} are authoritative for exact constraints;
 * encoded signals are a lossy numeric view and never substitute for them. Nothing here reads repositories,
 * task text, secrets or host execution context (Forge Routing Contract v1, issue #60).
 */
package monada.neuron.routing.features;
