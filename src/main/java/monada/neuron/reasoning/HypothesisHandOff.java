package monada.neuron.reasoning;

/**
 * A stage result that hands a {@link HypothesisSet} to the following stage.
 *
 * <p>{@link ReasoningCognitiveStageResult#hypothesesOf} reads any implementation, so an extension can offer
 * hypotheses to {@code EVALUATION} without the reasoning package depending on the extension.
 */
public interface HypothesisHandOff {

    /** The hypotheses offered to the following stage; empty when there are none. */
    HypothesisSet hypotheses();
}
