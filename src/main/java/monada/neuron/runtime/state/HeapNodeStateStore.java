package monada.neuron.runtime.state;

import monada.neuron.model.FrequencyState;

/**
 * Portable on-heap Structure-of-Arrays control for the FFM experiment.
 *
 * <p>The four arrays have the same logical order and access pattern as
 * {@link FfmNodeStateStore}. Closing this store invalidates access as well, so lifecycle tests and
 * callers use the same contract for both representations.
 */
public final class HeapNodeStateStore implements NodeStateStore {

    private final int size;
    private final long logicalBytes;
    private double[] amplitudes;
    private double[] frequencies;
    private double[] phases;
    private double[] energies;
    private boolean open;

    /** Creates an empty, positive-sized dense state store. */
    public HeapNodeStateStore(int size) {
        this.size = size;
        this.logicalBytes = NodeStateStoreSupport.logicalBytesFor(size);
        this.amplitudes = new double[size];
        this.frequencies = new double[size];
        this.phases = new double[size];
        this.energies = new double[size];
        this.open = true;
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
        return 0L;
    }

    @Override
    public double amplitudeAt(int index) {
        requireOpenIndex(index);
        return amplitudes[index];
    }

    @Override
    public double frequencyAt(int index) {
        requireOpenIndex(index);
        return frequencies[index];
    }

    @Override
    public double phaseAt(int index) {
        requireOpenIndex(index);
        return phases[index];
    }

    @Override
    public double energyAt(int index) {
        requireOpenIndex(index);
        return energies[index];
    }

    @Override
    public void setState(int index, FrequencyState state, double energy) {
        requireOpenIndex(index);
        NodeStateStoreSupport.validateState(state, energy);
        amplitudes[index] = state.amplitude();
        frequencies[index] = state.frequency();
        phases[index] = state.phase();
        energies[index] = energy;
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        amplitudes = null;
        frequencies = null;
        phases = null;
        energies = null;
    }

    private void requireOpenIndex(int index) {
        NodeStateStoreSupport.requireOpen(open);
        NodeStateStoreSupport.requireIndex(index, size);
    }
}
