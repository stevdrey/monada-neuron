package monada.neuron.action;

/**
 * How much of the action's produced observations the cognitive cycle admitted into its context.
 *
 * <p>This describes the cycle's capacity to retain and propagate the output, not what the action did:
 * the capability-reported {@link ActionStatus} is never changed by the cycle budget.
 */
public enum ObservationAdmission {

    /** Every produced observation was admitted. */
    COMPLETE,

    /** The cycle budget admitted only a proper prefix of the produced observations. */
    TRUNCATED
}
