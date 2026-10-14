package monada.neuron.routing.feedback;

import java.util.Objects;
import java.util.UUID;

/**
 * Caller-supplied binding of one cohort to the Node that holds its preference. Only the stable Node identifier is
 * carried, never the Node, so deriving feedback cannot mutate a target.
 *
 * @param key cohort the Node belongs to
 * @param targetNodeId identifier of the caller-owned Node
 */
public record TargetBinding(CohortKey key, UUID targetNodeId) {

    /** Requires both parts. */
    public TargetBinding {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");
    }
}
