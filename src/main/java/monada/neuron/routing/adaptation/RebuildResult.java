package monada.neuron.routing.adaptation;

import monada.neuron.routing.outcome.RoutingObservation;

import java.util.Objects;

/** What {@link RoutingStateStore#rebuild} produced. The live store is never modified either way. */
public sealed interface RebuildResult {

    /**
     * The staged replay succeeded; the caller swaps it in for the live store.
     *
     * @param store the rebuilt store
     */
    record Rebuilt(RoutingStateStore store) implements RebuildResult {

        /** Requires the store. */
        public Rebuilt {
            Objects.requireNonNull(store, "store must not be null");
        }
    }

    /**
     * The replay stopped; the staged copy was discarded, so no partial state is observable. The caller knows the live
     * state predates the correction and decides whether to keep using it.
     *
     * @param observation the observation the replay stopped at
     * @param update what consuming it reported
     */
    record Failed(RoutingObservation observation, StateUpdate update) implements RebuildResult {

        /** Requires the parts. */
        public Failed {
            Objects.requireNonNull(observation, "observation must not be null");
            Objects.requireNonNull(update, "update must not be null");
        }
    }
}
