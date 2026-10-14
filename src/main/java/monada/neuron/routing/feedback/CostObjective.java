package monada.neuron.routing.feedback;

import monada.neuron.routing.ResourceObjective;
import monada.neuron.routing.outcome.UsageKind;

import java.util.Objects;

/**
 * Declared objective of the optional cost refinement: which usage counter is compared and which end wins. It is
 * policy configuration, so a task or an outcome cannot steer it.
 *
 * @param kind usage counter compared between two directly comparable executions
 * @param direction whether the smaller or the larger known value is better
 */
public record CostObjective(UsageKind kind, ResourceObjective.Direction direction) {

    /** Requires both parts. */
    public CostObjective {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
    }
}
