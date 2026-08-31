package monada.neuron.runtime.state;

import monada.neuron.model.FrequencyState;

/**
 * Dense, mutable storage for the selected current state of a stable node population.
 *
 * <p>The physical representation is intentionally limited to amplitude, frequency, phase, and
 * energy. It does not own Node identity, type, topology, history, or any cognitive behaviour.
 * Dense indices are internal execution coordinates supplied by {@link NodeStateSnapshot}; they
 * are not a replacement for a Node UUID.
 *
 * <p>Implementations are not thread-safe. Callers own their lifecycle and must stop using the
 * store after {@link #close()}.
 */
public interface NodeStateStore extends AutoCloseable {

    /** Returns the number of dense state entries while the store is open. */
    int size();

    /** Returns whether the store can still be accessed. */
    boolean isOpen();

    /** Returns the logical payload size while open: four {@code double} channels per node. */
    long logicalBytes();

    /** Returns off-heap committed bytes while open, or zero for a heap-backed store. */
    long offHeapCommittedBytes();

    /** Returns the amplitude at {@code index}. */
    double amplitudeAt(int index);

    /** Returns the frequency at {@code index}. */
    double frequencyAt(int index);

    /** Returns the phase at {@code index}. */
    double phaseAt(int index);

    /** Returns the energy at {@code index}. */
    double energyAt(int index);

    /**
     * Materializes the immutable frequency state at {@code index} for a domain boundary.
     *
     * <p>Hot loops should use the primitive field accessors instead of allocating this value.
     */
    default FrequencyState stateAt(int index) {
        return new FrequencyState(amplitudeAt(index), frequencyAt(index), phaseAt(index));
    }

    /**
     * Replaces the four selected state components at {@code index}.
     *
     * <p>The operation does not mutate a source Node or record Node history. It only changes this
     * experimental execution buffer.
     */
    void setState(int index, FrequencyState state, double energy);

    /** Releases storage. Repeated calls are safe. */
    @Override
    void close();
}
