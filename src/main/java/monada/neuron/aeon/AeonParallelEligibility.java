package monada.neuron.aeon;

import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.NodeProcessor;

import java.util.List;

/**
 * Strategy interface to determine whether an Aeon coordination workload is eligible for parallel
 * execution.
 *
 * <p>Workloads that mutate shared node state, perform active adaptation against shared nodes, or
 * require strictly serialized side effects should return {@code false} to safely fall back to the
 * sequential reference path.
 */
@FunctionalInterface
public interface AeonParallelEligibility {

    /** Default eligibility policy assuming standard read-only node processing during propagation. */
    AeonParallelEligibility INDEPENDENT_READ_ONLY = (aeon, inputs, processor, config) -> true;

    /** Policy forcing sequential execution regardless of input count or processor. */
    AeonParallelEligibility SEQUENTIAL_ONLY = (aeon, inputs, processor, config) -> false;

    /**
     * Evaluates whether the coordination request satisfies independence and eligibility requirements.
     *
     * @param aeon capability and membership boundary
     * @param inputs ordered explicit Signal deliveries
     * @param processor focused processing behavior for reached Nodes
     * @param config bounded graph propagation behavior
     * @return {@code true} if parallel execution is permitted; {@code false} to force sequential fallback
     */
    boolean isEligible(
            Aeon aeon,
            List<AeonInput> inputs,
            NodeProcessor processor,
            PropagationConfig config);
}
