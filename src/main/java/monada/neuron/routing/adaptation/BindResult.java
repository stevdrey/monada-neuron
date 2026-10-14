package monada.neuron.routing.adaptation;

import monada.neuron.routing.feedback.CohortKey;

import java.util.List;
import java.util.Objects;

/** What {@link RoutingStateStore#bind} did. Binding is all-or-nothing. */
public sealed interface BindResult {

    /**
     * Every key is bound; the new ones start at the definition's initial state.
     *
     * @param added keys newly bound, in canonical order; empty when all were already bound
     */
    record Bound(List<CohortKey> added) implements BindResult {

        /** Copies the list. */
        public Bound {
            added = List.copyOf(Objects.requireNonNull(added, "added must not be null"));
        }
    }

    /**
     * Binding the new keys would exceed the cohort bound; nothing was bound. Dormant cohorts count toward the bound
     * and are removed only by an explicit {@link RoutingStateStore#unbindDormant} call.
     *
     * @param requested distinct new keys requested
     * @param available free cohort slots
     */
    record CapacityExhausted(int requested, int available) implements BindResult {
    }
}
