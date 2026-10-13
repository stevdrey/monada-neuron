package monada.neuron.routing.outcome;

/** Who produced a quality judgment; descriptive only, Neuron does not weigh sources. */
public enum EvidenceSource {
    TOOL, HUMAN, MODEL_JUDGE, IMPORTED
}
