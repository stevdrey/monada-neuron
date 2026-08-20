package monada.neuron.evolution;

import monada.neuron.model.Node;

/**
 * Functional contract for evaluating and applying deterministic state and energy adaptation to a Node.
 */
@FunctionalInterface
public interface AdaptationPolicy {

    /**
     * Evaluates feedback against the given node and applies state or energy transitions if applicable.
     *
     * @param node     the node to adapt (must not be null)
     * @param feedback typed feedback targeting the node (must not be null)
     * @return immutable decision describing previous and new states and whether adaptation was applied
     */
    AdaptationDecision adapt(Node node, FeedbackInput feedback);
}
