/**
 * Translation of bounded Resonance Store execution-sample exports into Neuron's neutral
 * {@link monada.neuron.routing.outcome.RoutingObservation}s (issue #64).
 *
 * <p>{@link monada.neuron.resonance.adapter.experience.StoreExperienceTranslator} is the only class that touches Store
 * types. Every other type here is Store-free. Reads are read-only: nothing is recorded, no retrieval feedback is
 * emitted, and no adaptation or learning happens.
 */
package monada.neuron.resonance.adapter.experience;
