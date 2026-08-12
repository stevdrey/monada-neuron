package monada.neuron.aeon;

import monada.neuron.runtime.graph.PropagationResult;

import java.util.Objects;

/**
 * Observable propagation result associated with one ordered Aeon input.
 *
 * @param input input that initiated the propagation
 * @param propagationResult complete result returned by the graph runtime
 */
public record AeonInputResult(AeonInput input, PropagationResult propagationResult) {

    /** Validates the input-to-result association. */
    public AeonInputResult {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(propagationResult, "propagationResult must not be null");
    }
}
