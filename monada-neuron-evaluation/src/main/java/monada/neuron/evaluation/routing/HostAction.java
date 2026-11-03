package monada.neuron.evaluation.routing;

/** What the scripted person chose to do with one decision's advice. */
enum HostAction {
    /** Run the advised route. */
    FOLLOW,
    /** Run another route the decision ranked or listed as eligible. */
    ALTERNATIVE,
    /** Postpone the task; nothing runs now. */
    DEFER,
    /** Refuse the task; nothing runs. */
    DECLINE
}
