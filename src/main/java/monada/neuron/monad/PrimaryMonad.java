package monada.neuron.monad;

import monada.neuron.aeon.Aeon;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The stable cognitive identity that owns an ordered set of coherent Aeon capabilities.
 *
 * <p>Registration retains the first Aeon instance for a UUID as canonical. The live member view
 * avoids copying low-volume orchestration state for every cognitive cycle. This type is not
 * thread-safe: registration must not change while a cycle is executing.
 */
public final class PrimaryMonad {

    private final UUID id;
    private final Map<UUID, Aeon> aeons;
    private final Collection<Aeon> aeonView;

    /** Creates an empty Primary Monad with caller-controlled stable identity. */
    public PrimaryMonad(UUID id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.aeons = new LinkedHashMap<>();
        this.aeonView = Collections.unmodifiableCollection(aeons.values());
    }

    /** Returns this Monad's stable identity. */
    public UUID getId() {
        return id;
    }

    /** Returns a live, unmodifiable view of registered Aeons in registration order. */
    public Collection<Aeon> getAeons() {
        return aeonView;
    }

    /**
     * Registers {@code aeon} when its UUID is not already present.
     *
     * @return {@code true} when the Aeon becomes canonical; {@code false} for an existing UUID
     */
    public boolean registerAeon(Aeon aeon) {
        Objects.requireNonNull(aeon, "aeon must not be null");
        return aeons.putIfAbsent(aeon.getId(), aeon) == null;
    }

    /**
     * Removes the canonical Aeon identified by {@code aeonId}.
     *
     * @return {@code true} when an Aeon was removed
     */
    public boolean unregisterAeon(UUID aeonId) {
        Objects.requireNonNull(aeonId, "aeonId must not be null");
        return aeons.remove(aeonId) != null;
    }

    /** Returns whether an Aeon UUID is currently registered. */
    public boolean containsAeon(UUID aeonId) {
        Objects.requireNonNull(aeonId, "aeonId must not be null");
        return aeons.containsKey(aeonId);
    }

    /** Returns the canonical Aeon for {@code aeonId}, when registered. */
    public Optional<Aeon> findAeon(UUID aeonId) {
        Objects.requireNonNull(aeonId, "aeonId must not be null");
        return Optional.ofNullable(aeons.get(aeonId));
    }

    /** Returns whether {@code aeon} is this Monad's canonical registered instance. */
    boolean isCanonicalAeon(Aeon aeon) {
        Objects.requireNonNull(aeon, "aeon must not be null");
        return aeons.get(aeon.getId()) == aeon;
    }

    /** Two Primary Monads are equal exactly when their stable UUIDs are equal. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PrimaryMonad other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "PrimaryMonad{id=" + id + ", aeons=" + aeons.size() + '}';
    }
}
