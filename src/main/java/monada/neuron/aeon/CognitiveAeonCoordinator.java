package monada.neuron.aeon;

import monada.neuron.context.CognitiveContext;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.NodeProcessor;

import java.util.List;

/** Optional Aeon-coordination extension that owns cycle-local context integration. */
public interface CognitiveAeonCoordinator extends AeonCoordinator {

    /**
     * Coordinates ordered inputs while recording bounded working state and deterministic trace
     * data in {@code context}.
     */
    AeonCoordinationResult coordinate(
            Aeon aeon,
            List<AeonInput> inputs,
            NodeProcessor processor,
            PropagationConfig config,
            CognitiveContext context);
}
