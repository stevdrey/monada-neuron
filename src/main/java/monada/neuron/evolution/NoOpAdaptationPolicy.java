package monada.neuron.evolution;

import monada.neuron.model.Node;

import java.util.Objects;

/**
 * Reference adaptation policy that performs no mutations, used as a baseline for A/B testing.
 */
public final class NoOpAdaptationPolicy implements AdaptationPolicy {

    /** Shared singleton instance. */
    public static final NoOpAdaptationPolicy INSTANCE = new NoOpAdaptationPolicy();

    /** Private constructor enforcing singleton usage. */
    private NoOpAdaptationPolicy() {
    }

    /**
     * Verifies target identity and returns an unchanged decision without mutating the node.
     */
    @Override
    public AdaptationDecision adapt(Node node, FeedbackInput feedback) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(feedback, "feedback must not be null");
        if (!node.getId().equals(feedback.targetNodeId())) {
            throw new IllegalArgumentException(
                    "feedback targetNodeId must match node id: " + feedback.targetNodeId() + " != " + node.getId());
        }
        var state = node.getFrequencyState();
        var energy = node.getEnergy();
        return new AdaptationDecision(node.getId(), false, state, state, energy, energy);
    }
}
