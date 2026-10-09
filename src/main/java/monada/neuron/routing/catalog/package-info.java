/**
 * Immutable, bounded worker-route catalog snapshots and deterministic, fail-closed eligibility filtering for the
 * opt-in Forge routing extension (Forge Routing Contract v1, issue #61).
 *
 * <p>Descriptors are caller observations, not proof of capability or authorization. Neuron performs no
 * discovery, quota polling or ranking here; an {@link monada.neuron.routing.catalog.EligibilityReport}
 * reserves nothing, so Forge must revalidate authorization, availability and quota before executing a route.
 */
package monada.neuron.routing.catalog;
