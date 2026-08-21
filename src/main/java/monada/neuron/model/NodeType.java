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
     * Participates in Neuron-local working or adaptive state behavior.
     *
     * <p>This role does not own long-term associative memory, persisted experience, recall
     * ranking, or storage compatibility. Durable or reusable experience intended for later
     * associative recall belongs to Monada Resonance Store and must be accessed through the
     * explicit resonance-memory boundary.
     */
    MEMORY
}
