package monada.neuron.routing.outcome;

/** How an attempt finished, as in the Store's {@code ATTEMPT_FINISHED} event. An unfinished attempt has no status. */
public enum AttemptStatus {
    COMPLETED, FAILED, CANCELLED
}
