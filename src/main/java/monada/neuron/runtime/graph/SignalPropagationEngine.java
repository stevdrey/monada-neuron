package monada.neuron.runtime.graph;

import monada.neuron.model.Node;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;

/**
 * Executes one bounded signal propagation through directed {@link Node} connections.
 *
 * <p>Implementations own graph traversal and ordering. Node processing remains delegated to the
 * supplied {@link NodeProcessor}, and node state must not be mutated by the propagation runtime.
 */
@FunctionalInterface
public interface SignalPropagationEngine {

    /**
     * Propagates {@code input} from {@code startNode} using the supplied processing and routing
     * behavior.
     *
     * @param startNode first node to process at hop zero
     * @param input initial signal presented to the first node
     * @param processor processing behavior applied to every reached node
     * @param config bounded propagation configuration
     * @return immutable observable propagation result
     */
    PropagationResult propagate(
            Node startNode,
            Signal input,
            NodeProcessor processor,
            PropagationConfig config);
}
