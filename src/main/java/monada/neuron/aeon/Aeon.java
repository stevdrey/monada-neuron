package monada.neuron.aeon;

import monada.neuron.model.Node;
import monada.neuron.model.NodeView;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A coherent cognitive capability composed from an ordered set of focused Nodes.
 *
 * <p>Identity and purpose are immutable. Membership is mutable and iterates in insertion order.
 * Adding a Node whose UUID is already present keeps the original canonical Node instance.
 * Removing and later re-adding a member places it at the end of the order.
 *
 * <p>The member view is live and unmodifiable, avoiding a defensive copy on each access.
 * This class is not thread-safe. Membership must not change during Aeon coordination, just as
 * Node state and topology must not change during graph propagation.
 */
public final class Aeon {

    private final UUID id;
    private final AeonPurpose purpose;
    private final Map<UUID, Node> members;
    private final Collection<Node> memberView;

    /** Creates an empty Aeon with caller-controlled deterministic identity. */
    public Aeon(UUID id, AeonPurpose purpose) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        this.members = new LinkedHashMap<>();
        this.memberView = Collections.unmodifiableCollection(members.values());
    }

    /** Returns this Aeon's durable identity. */
    public UUID getId() {
        return id;
    }

    /** Returns the coherent cognitive purpose owned by this Aeon. */
    public AeonPurpose getPurpose() {
        return purpose;
    }

    /**
     * Returns a live, unmodifiable view of members in insertion order.
     *
     * @return ordered member view
     */
    public Collection<Node> getMembers() {
        return memberView;
    }

    /**
     * Adds {@code node} as a member if its UUID is not already present.
     *
     * @return {@code true} when added; {@code false} when that UUID was already a member
     */
    public boolean addMember(Node node) {
        Objects.requireNonNull(node, "node must not be null");
        return members.putIfAbsent(node.getId(), node) == null;
    }

    /**
     * Removes the member identified by {@code nodeId}.
     *
     * @return {@code true} when a member was removed; {@code false} when it was absent
     */
    public boolean removeMember(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return members.remove(nodeId) != null;
    }

    /** Returns whether {@code nodeId} identifies a current member. */
    public boolean containsMember(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return members.containsKey(nodeId);
    }

    /** Returns the canonical member for {@code nodeId}, when present. */
    public Optional<Node> findMember(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return Optional.ofNullable(members.get(nodeId));
    }

    /** Returns whether {@code node} is the canonical member instance stored for its UUID. */
    boolean isCanonicalMember(NodeView node) {
        Objects.requireNonNull(node, "node must not be null");
        return members.get(node.getId()) == node;
    }

    /** Two Aeons are equal exactly when they have the same immutable UUID. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Aeon other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Aeon{id=" + id
                + ", purpose=" + purpose
                + ", members=" + members.size()
                + '}';
    }
}
