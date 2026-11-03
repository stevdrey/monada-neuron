package monada.neuron.evaluation.routing;

import monada.neuron.routing.catalog.RouteKey;

import java.util.Objects;

/**
 * What a host runs for one decision. Only a {@code Selected} decision executes: over an abstention or no eligible
 * route the host can only report {@link NotExecuted}, because v1 feedback answers only a selection.
 */
sealed interface HostExecution {

    /** The host runs the advised route. */
    record Advised() implements HostExecution {
    }

    /**
     * The host runs another route than the advised one, answering the same decision.
     *
     * @param route the route that runs
     */
    record Alternative(RouteKey route) implements HostExecution {

        /** Requires the route. */
        public Alternative {
            Objects.requireNonNull(route, "route must not be null");
        }
    }

    /**
     * Nothing runs.
     *
     * @param action what the person chose, which led to no run
     */
    record NotExecuted(HostAction action) implements HostExecution {

        /** Requires the action. */
        public NotExecuted {
            Objects.requireNonNull(action, "action must not be null");
        }
    }
}
