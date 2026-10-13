package monada.neuron.resonance.adapter.experience;

import monada.neuron.routing.outcome.HypotheticalCost;

import java.util.List;

/**
 * Optional host seam for evaluation-only, API-equivalent cost annotations. The Store's production export carries usage
 * only; cost comes from evaluation tooling that Neuron production code must not depend on, so the host may supply it here.
 * Hypothetical cost is kept apart from actual billing and never changes the evidence.
 */
@FunctionalInterface
public interface HypotheticalCostAnnotations {

    /**
     * Costs for one execution.
     *
     * @param scopeId scope
     * @param taskId task
     * @param executionId execution
     * @return at most one cost per currency; empty when none is known
     */
    List<HypotheticalCost> costsFor(String scopeId, String taskId, String executionId);

    /**
     * No annotations.
     *
     * @return an implementation that supplies nothing
     */
    static HypotheticalCostAnnotations none() {
        return (scopeId, taskId, executionId) -> List.of();
    }
}
