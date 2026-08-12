package monada.neuron.aeon;

import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.NodeProcessor;

import java.util.List;

/** Coordinates ordered Signal inputs within one Aeon's membership boundary. */
@FunctionalInterface
public interface AeonCoordinator {

    /**
     * Coordinates {@code inputs} sequentially in their declared order.
     *
     * @param aeon cognitive capability and membership boundary
     * @param inputs ordered explicit Signal deliveries
     * @param processor focused processing behavior for reached Nodes
     * @param config bounded graph propagation behavior applied independently to each input
     * @return immutable result preserving one propagation result per input
     */
    AeonCoordinationResult coordinate(
            Aeon aeon,
            List<AeonInput> inputs,
            NodeProcessor processor,
            PropagationConfig config);
}
