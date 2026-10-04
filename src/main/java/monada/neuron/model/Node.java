package monada.neuron.model;

import java.util.AbstractList;
import java.util.Collections;
import java.util.RandomAccess;
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

    /**
     * Default maximum number of past {@link FrequencyState} values retained per node.
     *
     * <p>Repeated adaptation across cognitive cycles appends one state per transition, so retention
     * is bounded by default; the oldest state is overwritten once the limit is reached.
     */
    public static final int DEFAULT_HISTORY_LIMIT = 256;

    private static final int INITIAL_HISTORY_CAPACITY = 4;

    /** Immutable unique identifier for this node. */
    private final UUID id;

    /**
     * Current wave/frequency state of the node.
     * Replaced atomically on each state transition to keep history consistent.
     */
    private FrequencyState frequencyState;

    /**
     * Ring buffer of past {@link FrequencyState} values, oldest at {@link #historyHead}.
     * Lazily initialised: {@code null} until the first state transition is recorded, and grown by
     * doubling up to {@link #historyLimit}. This avoids allocating storage for every node when
     * history is not needed and keeps memory bounded when it is.
     */
    private FrequencyState[] history;

    /** Index of the oldest retained state inside {@link #history}. */
    private int historyHead;

    /** Number of retained states in {@link #history}. */
    private int historySize;

    /** Maximum number of retained states; zero disables history. */
    private final int historyLimit;

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

    /**
     * Monotonic structural version for runtime snapshots of {@link #connections}.
     *
     * <p>The value changes only after a successful connection addition or removal. It is not a
     * concurrency primitive: callers must still keep topology mutation and traversal sequential.
     */
    private long topologyVersion;

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
        this.topologyVersion = 0L;
        this.historyLimit = builder.historyLimit;
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
     * from oldest to most recent, limited to the most recent {@link #getHistoryLimit()} states.
     * Returns an empty list if history has never been recorded. The returned list is a snapshot of
     * the buffer at call time and is not affected by later transitions. Taking it costs O(size); use
     * {@link #getHistorySize()} when only the count is needed.
     */
    public List<FrequencyState> getHistory() {
        return historySize == 0 ? List.of() : new HistoryView(history, historyHead, historySize);
    }

    /**
     * Returns how many past states are currently retained, in O(1) and without materializing them.
     * Prefer this over {@code getHistory().size()}, which copies every retained state.
     */
    public int getHistorySize() {
        return historySize;
    }

    /** Returns the maximum number of past states this node retains; zero means history is disabled. */
    public int getHistoryLimit() {
        return historyLimit;
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

    /**
     * Returns the version of this node's directed connection set.
     *
     * <p>A compact runtime snapshot uses this value to detect that its compiled adjacency is no
     * longer current. Frequency-state and energy changes intentionally do not affect it.
     */
    public long getTopologyVersion() {
        return topologyVersion;
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
        if (connections.contains(target)) {
            return false;
        }
        requireTopologyVersionCapacity();
        boolean connected = connections.add(target);
        if (connected) {
            topologyVersion++;
        }
        return connected;
    }

    /**
     * Removes the connection from this node to {@code target}.
     *
     * @param target the node to disconnect from (must not be {@code null})
     * @return {@code true} if the connection existed and was removed; {@code false} otherwise
     */
    public boolean disconnect(Node target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!connections.contains(target)) {
            return false;
        }
        requireTopologyVersionCapacity();
        boolean disconnected = connections.remove(target);
        if (disconnected) {
            topologyVersion++;
        }
        return disconnected;
    }

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    /**
     * Appends {@code state}, overwriting the oldest retained state once {@link #historyLimit} is
     * reached. Append is O(1) amortised and never shifts elements.
     */
    private void recordHistory(FrequencyState state) {
        if (historyLimit == 0) {
            return;
        }
        if (history == null) {
            history = new FrequencyState[Math.min(INITIAL_HISTORY_CAPACITY, historyLimit)];
        }
        if (historySize == history.length && history.length < historyLimit) {
            growHistory();
        }
        if (historySize < history.length) {
            history[(historyHead + historySize) % history.length] = state;
            historySize++;
        } else {
            history[historyHead] = state;
            historyHead = (historyHead + 1) % history.length;
        }
    }

    /** Doubles the ring capacity (bounded by the limit), re-linearising so the oldest is at 0. */
    private void growHistory() {
        int newCapacity = (int) Math.min((long) history.length * 2, historyLimit);
        var grown = new FrequencyState[newCapacity];
        for (int i = 0; i < historySize; i++) {
            grown[i] = history[(historyHead + i) % history.length];
        }
        history = grown;
        historyHead = 0;
    }

    /** Immutable ordered view over a copy of the ring contents, oldest first. */
    private static final class HistoryView extends AbstractList<FrequencyState> implements RandomAccess {

        private final FrequencyState[] ordered;

        HistoryView(FrequencyState[] ring, int head, int size) {
            this.ordered = new FrequencyState[size];
            for (int i = 0; i < size; i++) {
                this.ordered[i] = ring[(head + i) % ring.length];
            }
        }

        @Override
        public FrequencyState get(int index) {
            return ordered[Objects.checkIndex(index, ordered.length)];
        }

        @Override
        public int size() {
            return ordered.length;
        }
    }

    private void requireTopologyVersionCapacity() {
        if (topologyVersion == Long.MAX_VALUE) {
            throw new IllegalStateException("topology version cannot advance beyond Long.MAX_VALUE");
        }
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
     *   <li>{@code historyLimit} — {@link Node#DEFAULT_HISTORY_LIMIT}</li>
     * </ul>
     */
    public static final class Builder {

        private UUID id = UUID.randomUUID();
        private FrequencyState frequencyState = FrequencyState.ZERO;
        private double energy = 0.0;
        private NodeType type;
        private int historyLimit = DEFAULT_HISTORY_LIMIT;
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
         * Sets the maximum number of past frequency states retained by the node.
         *
         * @param historyLimit retained states; zero disables history
         * @throws IllegalArgumentException if {@code historyLimit} is negative
         */
        public Builder historyLimit(int historyLimit) {
            if (historyLimit < 0) {
                throw new IllegalArgumentException("historyLimit must be non-negative, got: " + historyLimit);
            }
            this.historyLimit = historyLimit;
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
