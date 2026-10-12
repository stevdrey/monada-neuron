package monada.neuron.routing.outcome;

/** Judgment of one dimension. {@code UNKNOWN} is never a pass; {@code NOT_APPLICABLE} needs a justification. */
public enum QualityState {
    PASS, FAIL, UNKNOWN, NOT_APPLICABLE
}
