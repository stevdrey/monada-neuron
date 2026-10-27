package monada.neuron.evaluation.wave;

/** Role of a fixture split (contract section 12.3); each role is generated from its own seed. */
public enum SplitRole {

    /** Read by training; its labels are open. */
    TRAINING,
    /** Read by threshold selection; its labels are open. */
    THRESHOLD_SELECTION,
    /** Held out; its labels are guarded and read only by the scorer. */
    TEST
}
