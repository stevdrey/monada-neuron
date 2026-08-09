package monada.neuron.model;

import java.util.UUID;

/**
 * Read-only view of the node state available during signal processing.
 *
 * <p>The processing contract intentionally excludes graph connections and mutation methods.
 * Graph traversal belongs to the runtime, while state transitions belong to explicit adaptation
 * or evolution behavior.
 */
public interface NodeView {

    /**
     * Returns the durable identity of the observed node.
     *
     * @return node UUID
     */
    UUID getId();

    /**
     * Returns the node's current immutable frequency state.
     *
     * @return current frequency state
     */
    FrequencyState getFrequencyState();

    /**
     * Returns the node's current energy level.
     *
     * @return non-negative finite energy
     */
    double getEnergy();

    /**
     * Returns the node's cognitive role.
     *
     * @return node classification
     */
    NodeType getType();
}
