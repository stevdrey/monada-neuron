package monada.neuron.routing.comparison;

/**
 * A coded label every {@link ScenarioComparison} carries, in this order. Forge owns the wording and its localization.
 */
public enum PreviewNotice {
    /**
     * Both sides are hypothetical advice: a different selection establishes neither how a route would perform nor what
     * it would spend, until the host separately runs it and judges the outcome.
     */
    HYPOTHETICAL,
    /** Neither side is permission: the host rechecks authorization, availability and quota before any execution. */
    RECHECK_BEFORE_EXECUTION
}
