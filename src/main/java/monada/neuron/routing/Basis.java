package monada.neuron.routing;

/** What made the selected route win over the runner-up. */
public enum Basis {
    /** No host priority, learned or resource signal separated the leaders: canonical order (or a sole route). */
    COLD_START,
    /** Host tier or fallback priority separated the leaders. */
    HOST_PRIORITY,
    /** Learned preference separated the leaders. */
    LEARNED_PREFERENCE,
    /** The resource objective separated the leaders. */
    RESOURCE_OBJECTIVE
}
