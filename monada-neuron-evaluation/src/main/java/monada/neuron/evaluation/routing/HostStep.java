package monada.neuron.evaluation.routing;

import monada.neuron.routing.RoutingDecision;

/**
 * The host between a decision and its execution in a {@link TemporalReplay}: asked once per decision of a task whose
 * outcomes are scripted for every route, it says what runs.
 */
@FunctionalInterface
interface HostStep {

    /** Runs the advised route of every selection and nothing otherwise: the replay's behavior without a host. */
    HostStep FOLLOW = (task, decision) -> decision instanceof RoutingDecision.Selected
            ? new HostExecution.Advised() : new HostExecution.NotExecuted(HostAction.FOLLOW);

    /**
     * What the host runs for one decision.
     *
     * @param task the task being decided
     * @param decision the decision the policy returned for it
     * @return the execution; anything but {@link HostExecution.NotExecuted} over a non-selection is a harness bug
     */
    HostExecution execute(ReplayTask task, RoutingDecision decision);
}
