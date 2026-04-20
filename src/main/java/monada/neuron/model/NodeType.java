package monada.neuron.model;

/**
 * Classifies the role of a {@link Node} within the network graph.
 *
 * <p>This taxonomy is intentionally coarse for Phase 1. Future phases may introduce
 * sub-types or a sealed hierarchy to support richer classification (e.g., ATTENTION, GATE).
 */
public enum NodeType {

    /**
     * Receives external signals; entry points into the network.
     * Typically has no inbound connections from other nodes.
     */
    INPUT,

    /**
     * Transforms or routes frequency signals between nodes.
     * The primary computational unit of the network.
     */
    PROCESSOR,

    /**
     * Retains historical frequency states for recall and pattern recognition.
     * May persist state across multiple time steps.
     */
    MEMORY
}
