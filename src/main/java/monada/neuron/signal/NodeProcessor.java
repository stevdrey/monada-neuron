package monada.neuron.signal;

import monada.neuron.model.NodeView;

/**
 * Focused cognitive operation that observes one node and processes one input signal.
 *
 * <p>Implementations must not mutate the observed node or hidden global state. Successful
 * processing returns every emitted signal explicitly. Processing failures propagate to the
 * caller and must not be represented as an empty output.
 */
@FunctionalInterface
public interface NodeProcessor {

    /**
     * Processes {@code input} using the current read-only state of {@code node}.
     *
     * @param node node state available to the operation
     * @param input input signal
     * @return explicit ordered processing output; never {@code null}
     */
    NodeProcessingResult process(NodeView node, Signal input);
}
