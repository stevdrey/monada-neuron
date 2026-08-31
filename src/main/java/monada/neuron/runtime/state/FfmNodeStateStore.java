package monada.neuron.runtime.state;

import monada.neuron.model.FrequencyState;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * FFM-backed Structure-of-Arrays state storage for the large-topology experiment.
 *
 * <p>One confined {@link Arena} owns four naturally aligned contiguous channels: amplitude,
 * frequency, phase, and energy. Their element layout uses the platform's native byte order. The
 * store deliberately uses index-based access rather than per-read slices, and neither arena nor
 * segment details escape the {@link NodeStateStore} contract.
 *
 * <p>This store is confined to the creating thread. It has no synchronization or shared-memory
 * semantics; callers must close it on that owner thread before discarding the experimental view.
 */
public final class FfmNodeStateStore implements NodeStateStore {

    private static final ValueLayout.OfDouble NATIVE_DOUBLE =
            ValueLayout.JAVA_DOUBLE.withOrder(ByteOrder.nativeOrder());

    private final int size;
    private final long logicalBytes;
    private final Arena arena;
    private final MemorySegment amplitudes;
    private final MemorySegment frequencies;
    private final MemorySegment phases;
    private final MemorySegment energies;
    private boolean open;

    /** Allocates four equivalent contiguous native channels in one confined arena. */
    public FfmNodeStateStore(int size) {
        this.size = size;
        this.logicalBytes = NodeStateStoreSupport.logicalBytesFor(size);
        this.arena = Arena.ofConfined();
        try {
            MemoryLayout channelLayout = MemoryLayout.sequenceLayout(size, NATIVE_DOUBLE)
                    .withByteAlignment(NATIVE_DOUBLE.byteAlignment());
            this.amplitudes = arena.allocate(channelLayout);
            this.frequencies = arena.allocate(channelLayout);
            this.phases = arena.allocate(channelLayout);
            this.energies = arena.allocate(channelLayout);
            this.open = true;
        } catch (RuntimeException | Error exception) {
            arena.close();
            throw exception;
        }
    }

    @Override
    public int size() {
        NodeStateStoreSupport.requireOpen(open);
        return size;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public long logicalBytes() {
        NodeStateStoreSupport.requireOpen(open);
        return logicalBytes;
    }

    @Override
    public long offHeapCommittedBytes() {
        NodeStateStoreSupport.requireOpen(open);
        return logicalBytes;
    }

    @Override
    public double amplitudeAt(int index) {
        requireOpenIndex(index);
        return amplitudes.getAtIndex(NATIVE_DOUBLE, index);
    }

    @Override
    public double frequencyAt(int index) {
        requireOpenIndex(index);
        return frequencies.getAtIndex(NATIVE_DOUBLE, index);
    }

    @Override
    public double phaseAt(int index) {
        requireOpenIndex(index);
        return phases.getAtIndex(NATIVE_DOUBLE, index);
    }

    @Override
    public double energyAt(int index) {
        requireOpenIndex(index);
        return energies.getAtIndex(NATIVE_DOUBLE, index);
    }

    @Override
    public void setState(int index, FrequencyState state, double energy) {
        requireOpenIndex(index);
        NodeStateStoreSupport.validateState(state, energy);
        amplitudes.setAtIndex(NATIVE_DOUBLE, index, state.amplitude());
        frequencies.setAtIndex(NATIVE_DOUBLE, index, state.frequency());
        phases.setAtIndex(NATIVE_DOUBLE, index, state.phase());
        energies.setAtIndex(NATIVE_DOUBLE, index, energy);
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        arena.close();
        open = false;
    }

    private void requireOpenIndex(int index) {
        NodeStateStoreSupport.requireOpen(open);
        NodeStateStoreSupport.requireIndex(index, size);
    }
}
