package monada.neuron.runtime.graph;

import monada.neuron.context.CognitiveContext;
import monada.neuron.model.Node;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;

/**
 * Optional propagation extension that participates in one bounded {@link CognitiveContext}.
 *
 * <p>The base {@link SignalPropagationEngine} contract remains available for callers that do not
 * require cycle-local tracing or global resource limits.
 */
public interface CognitiveSignalPropagationEngine extends SignalPropagationEngine {

    /**
     * Propagates one input while consuming the supplied cycle context's shared budget.
     *
     * @return immutable per-propagation result; cycle-budget state is reported by the context
     */
    PropagationResult propagate(
            Node startNode,
            Signal input,
            NodeProcessor processor,
            PropagationConfig config,
            CognitiveContext context);
}
