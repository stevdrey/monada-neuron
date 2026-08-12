package monada.neuron.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Core abstraction of the Monada-Neuron system.
 *
 * <p>A {@code Node} represents a unit of knowledge encoded as a frequency-based wave pattern
 * rather than a static vector. Interaction between nodes emerges from frequency resonance,
 * avoiding the need for explicit message-passing protocols.
 *
 * <p><strong>Design principles:</strong>
 * <ul>
 *   <li>Identity is solely determined by {@link #id}; two nodes with the same UUID are the same
 *       node regardless of any mutable state.</li>
 *   <li>{@link #frequencyState} and {@link #energy} are mutable to support in-place learning
 *       updates, but mutations are intentionally coarse-grained (full-state replacement) to
 *       keep the history accurate and auditable.</li>
 *   <li>{@link #connections} is exposed as an unmodifiable view; structural changes must go
 *       through {@link #connect(Node)} / {@link #disconnect(Node)} to maintain invariants.</li>
 *   <li>{@link #history} is lazily initialised to minimise heap pressure when running millions
 *       of nodes where history tracking is not required.</li>
 * </ul>
 *
 * <p><strong>Thread safety:</strong> This class is <em>not</em> thread-safe. External
 * synchronisation is required if nodes are mutated from multiple threads. This is a deliberate
 * Phase 1 decision; concurrent access patterns will be addressed in a future phase.
 *
 * <p>Use {@link Node.Builder} to construct instances.
 */
public final class Node implements NodeView {

    /** Immutable unique identifier for this node. */
    private final UUID id;

    /**
     * Current wave/frequency state of the node.
     * Replaced atomically on each state transition to keep history consistent.
     */
    private FrequencyState frequencyState;

    /**
     * Ordered record of past {@link FrequencyState} values.
     * Lazily initialised: {@code null} until the first state transition is recorded.
     * This avoids allocating a list for every node when history is not needed.
     */
    private List<FrequencyState> history;

    /**
     * Scalar activation/intensity level.
     * Represents how "active" or "energised" this node is at the current time step.
     * Range: [0.0, ∞). Must be non-negative.
     */
    private double energy;

    /** Role classification of this node in the network. */
    private final NodeType type;

    /**
     * Adjacency set for graph-based connectivity.
     * Using a {@link HashSet} gives O(1) average connect/disconnect/membership checks.
     * Exposed externally as an unmodifiable view.
     */
    private final Set<Node> connections;

    /** Unmodifiable view over {@link #connections} returned by {@link #getConnections()}. */
    private final Set<Node> connectionsView;

    // -------------------------------------------------------------------------
    // Constructor (package-private; use Builder)
    // -------------------------------------------------------------------------

    private Node(Builder builder) {
        this.id = builder.id;
        this.frequencyState = builder.frequencyState;
        this.energy = builder.energy;
        this.type = builder.type;
        this.connections = new HashSet<>(builder.connections);
        this.connectionsView = Collections.unmodifiableSet(this.connections);
        // History is not pre-populated; only future transitions are recorded.
        this.history = null;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the unique identifier of this node. */
    public UUID getId() {
        return id;
    }

    /** Returns the current frequency state. */
    public FrequencyState getFrequencyState() {
        return frequencyState;
    }

    /**
     * Returns an unmodifiable ordered list of historical {@link FrequencyState} values,
     * from oldest to most recent. Returns an empty list if history has never been recorded.
     */
    public List<FrequencyState> getHistory() {
        return history == null ? List.of() : Collections.unmodifiableList(history);
    }

    /** Returns the current energy/activation level. */
    public double getEnergy() {
        return energy;
    }

    /** Returns the type/role classification of this node. */
    public NodeType getType() {
        return type;
    }

    /**
     * Returns an unmodifiable view of the directly connected nodes.
     * The returned set reflects live structural changes made via {@link #connect(Node)} and
     * {@link #disconnect(Node)}. Its iteration order is unspecified and must not be used as an
     * execution-order contract; graph runtimes are responsible for defining observable traversal
     * order explicitly.
     */
    public Set<Node> getConnections() {
        return connectionsView;
    }

    // -------------------------------------------------------------------------
    // State mutation
    // -------------------------------------------------------------------------

    /**
     * Transitions the node to a new frequency state.
     * The previous state is appended to {@link #history} before the transition occurs.
     *
     * @param newState the new frequency state (must not be {@code null})
     */
    public void transition(FrequencyState newState) {
        Objects.requireNonNull(newState, "newState must not be null");
        recordHistory(this.frequencyState);
        this.frequencyState = newState;
    }

    /**
     * Updates the energy level of this node.
     *
     * @param energy new energy value (must be non-negative and finite)
     * @throws IllegalArgumentException if {@code energy} is negative or non-finite
     */
    public void setEnergy(double energy) {
        if (!Double.isFinite(energy)) {
            throw new IllegalArgumentException("energy must be finite, got: " + energy);
        }
        if (energy < 0) {
            throw new IllegalArgumentException("energy must be non-negative, got: " + energy);
        }
        this.energy = energy;
    }

    // -------------------------------------------------------------------------
    // Graph connectivity
    // -------------------------------------------------------------------------

    /**
     * Adds a directed connection from this node to {@code target}.
     * Self-connections are not permitted (checked by UUID identity).
     *
     * @param target the node to connect to (must not be {@code null} and must have a different UUID)
     * @return {@code true} if the connection was newly added; {@code false} if it already existed
     * @throws IllegalArgumentException if {@code target} has the same UUID as this node
     */
    public boolean connect(Node target) {
        Objects.requireNonNull(target, "target must not be null");
        if (target.id.equals(this.id)) {
            throw new IllegalArgumentException("A node cannot connect to itself (UUID: " + this.id + ")");
        }
        return connections.add(target);
    }

    /**
     * Removes the connection from this node to {@code target}.
     *
     * @param target the node to disconnect from (must not be {@code null})
     * @return {@code true} if the connection existed and was removed; {@code false} otherwise
     */
    public boolean disconnect(Node target) {
        Objects.requireNonNull(target, "target must not be null");
        return connections.remove(target);
    }

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    /** Lazily initialises {@link #history} and appends {@code state}. */
    private void recordHistory(FrequencyState state) {
        if (history == null) {
            history = new ArrayList<>();
        }
        history.add(state);
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /**
     * Two nodes are equal if and only if they share the same {@link UUID}.
     * Mutable fields (state, energy, connections) do not participate in equality.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Node other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Node{id=" + id
                + ", type=" + type
                + ", energy=" + energy
                + ", frequencyState=" + frequencyState
                + ", connections=" + connections.size()
                + '}';
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /**
     * Fluent builder for {@link Node}.
     *
     * <p>Only {@link #type(NodeType)} is mandatory. All other fields have sensible defaults:
     * <ul>
     *   <li>{@code id} — randomly generated {@link UUID}</li>
     *   <li>{@code frequencyState} — {@link FrequencyState#ZERO}</li>
     *   <li>{@code energy} — {@code 0.0}</li>
     *   <li>{@code connections} — empty set</li>
     * </ul>
     */
    public static final class Builder {

        private UUID id = UUID.randomUUID();
        private FrequencyState frequencyState = FrequencyState.ZERO;
        private double energy = 0.0;
        private NodeType type;
        private final Set<Node> connections = new HashSet<>();

        /** Sets a specific UUID (useful for deserialization or deterministic testing). */
        public Builder id(UUID id) {
            this.id = Objects.requireNonNull(id, "id must not be null");
            return this;
        }

        /** Sets the initial frequency state. */
        public Builder frequencyState(FrequencyState frequencyState) {
            this.frequencyState = Objects.requireNonNull(frequencyState, "frequencyState must not be null");
            return this;
        }

        /**
         * Sets the initial energy level.
         *
         * @param energy must be non-negative and finite
         * @throws IllegalArgumentException if {@code energy} is negative or non-finite
         */
        public Builder energy(double energy) {
            if (!Double.isFinite(energy)) {
                throw new IllegalArgumentException("energy must be finite, got: " + energy);
            }
            if (energy < 0) {
                throw new IllegalArgumentException("energy must be non-negative, got: " + energy);
            }
            this.energy = energy;
            return this;
        }

        /**
         * Sets the node type. <strong>Required.</strong>
         */
        public Builder type(NodeType type) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            return this;
        }

        /** Adds an initial connection. Self-connections will be rejected at build time. */
        public Builder connection(Node node) {
            this.connections.add(Objects.requireNonNull(node, "connection node must not be null"));
            return this;
        }

        /**
         * Builds and returns the {@link Node}.
         *
         * @throws IllegalStateException if {@link #type} has not been set
         * @throws IllegalArgumentException if any connection has the same UUID as the node being built
         */
        public Node build() {
            if (type == null) {
                throw new IllegalStateException("NodeType must be specified via type(NodeType)");
            }
            for (Node connection : connections) {
                if (connection.id.equals(this.id)) {
                    throw new IllegalArgumentException("A node cannot connect to itself (UUID: " + this.id + ")");
                }
            }
            return new Node(this);
        }
    }
}
