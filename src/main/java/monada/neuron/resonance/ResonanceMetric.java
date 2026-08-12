package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;

/**
 * Measures the resonance between two frequency states.
 *
 * <p>Implementations must document their numerical range and semantic properties. A metric score
 * represents only the relationship between the supplied states; ranking and tie-breaking remain
 * responsibilities of the caller.
 */
@FunctionalInterface
public interface ResonanceMetric {

    /**
     * Returns the resonance score between two valid frequency states.
     *
     * @param first first frequency state
     * @param second second frequency state
     * @return the metric-specific resonance score
     * @throws NullPointerException if either state is {@code null}
     */
    double score(FrequencyState first, FrequencyState second);
}
