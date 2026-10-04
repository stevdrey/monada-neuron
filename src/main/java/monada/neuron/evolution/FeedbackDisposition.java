package monada.neuron.evolution;

/** Explicit direction of cross-cycle feedback derived from one observed action outcome. */
public enum FeedbackDisposition {

    /** The outcome supports the targeted Nodes; every entry carries a positive score. */
    REINFORCE,

    /** The outcome counts against the targeted Nodes; every entry carries a negative score. */
    PENALIZE,

    /**
     * The outcome is not actionable for adaptation, such as an environmental unavailability or
     * timeout. The feedback carries no entries so no reward or penalty is fabricated.
     */
    NEUTRAL
}
