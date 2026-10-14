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

    /**
     * The ledger still records applied effects for these previously removed cohorts; binding a baseline Node under
     * them would make the published preference disagree with the ledger. Nothing was bound. The caller restores them with
     * one staged {@code RoutingStateStore.rebuild} whose cohort set includes these keys, which replays their samples in
     * applied order.
     *
     * @param credited the requested keys the ledger still credits, in canonical order
     */
    record RebuildRequired(List<CohortKey> credited) implements BindResult {

        /** Copies the list and requires at least one key. */
        public RebuildRequired {
            credited = List.copyOf(Objects.requireNonNull(credited, "credited must not be null"));
            if (credited.isEmpty()) {
                throw new IllegalArgumentException("a rebuild-required bind names at least one credited key");
            }
        }
    }
}
