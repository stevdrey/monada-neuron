package monada.neuron.resonance;

import monada.neuron.model.FrequencyState;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Contiguous, immutable Structure-of-Arrays (SoA) batch container for {@link FrequencyState} values.
 *
 * <p>Stores amplitudes, frequencies, and phases in parallel primitive {@code double[]} arrays,
 * enabling direct vectorized SIMD memory loads and eliminating pointer indirection.
 */
public final class FrequencyStateBatch {

    private final double[] amplitudes;
    private final double[] frequencies;
    private final double[] phases;
    private final int size;

    /**
     * Constructs a batch of frequency states with validation and defensive copying.
     *
     * @param amplitudes array of amplitudes (must be finite and non-negative)
     * @param frequencies array of frequencies (must be finite and non-negative)
     * @param phases array of phases (must be finite)
     * @param size number of valid elements
     * @throws NullPointerException if any array is {@code null}
     * @throws IllegalArgumentException if size is negative, arrays are shorter than size, or any value is invalid
     */
    public FrequencyStateBatch(double[] amplitudes, double[] frequencies, double[] phases, int size) {
        this(amplitudes, frequencies, phases, size, false);
    }

    private FrequencyStateBatch(double[] amplitudes, double[] frequencies, double[] phases, int size, boolean trusted) {
        Objects.requireNonNull(amplitudes, "amplitudes must not be null");
        Objects.requireNonNull(frequencies, "frequencies must not be null");
        Objects.requireNonNull(phases, "phases must not be null");

        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative, got: " + size);
        }
        if (amplitudes.length < size || frequencies.length < size || phases.length < size) {
            throw new IllegalArgumentException(String.format(
                    "array length smaller than size: amplitudes=%d, frequencies=%d, phases=%d, size=%d",
                    amplitudes.length, frequencies.length, phases.length, size));
        }

        if (!trusted) {
            for (int i = 0; i < size; i++) {
                double a = amplitudes[i];
                double f = frequencies[i];
                double p = phases[i];

                if (!Double.isFinite(a) || a < 0.0) {
                    throw new IllegalArgumentException("amplitude at index " + i + " must be non-negative and finite, got: " + a);
                }
                if (!Double.isFinite(f) || f < 0.0) {
                    throw new IllegalArgumentException("frequency at index " + i + " must be non-negative and finite, got: " + f);
                }
                if (!Double.isFinite(p)) {
                    throw new IllegalArgumentException("phase at index " + i + " must be finite, got: " + p);
                }
            }
            this.amplitudes = Arrays.copyOf(amplitudes, size);
            this.frequencies = Arrays.copyOf(frequencies, size);
            this.phases = Arrays.copyOf(phases, size);
        } else {
            this.amplitudes = amplitudes;
            this.frequencies = frequencies;
            this.phases = phases;
        }
        this.size = size;
    }

    /**
     * Creates a batch from complete parallel primitive arrays of equal length.
     *
     * @param amplitudes array of amplitudes
     * @param frequencies array of frequencies
     * @param phases array of phases
     * @return the immutable batch
     */
    public static FrequencyStateBatch of(double[] amplitudes, double[] frequencies, double[] phases) {
        Objects.requireNonNull(amplitudes, "amplitudes must not be null");
        Objects.requireNonNull(frequencies, "frequencies must not be null");
        Objects.requireNonNull(phases, "phases must not be null");
        if (amplitudes.length != frequencies.length || amplitudes.length != phases.length) {
            throw new IllegalArgumentException(String.format(
                    "arrays must have equal lengths: amplitudes=%d, frequencies=%d, phases=%d",
                    amplitudes.length, frequencies.length, phases.length));
        }
        return new FrequencyStateBatch(amplitudes, frequencies, phases, amplitudes.length);
    }

    /**
     * Creates a batch from an array of {@link FrequencyState} instances.
     *
     * @param states array of frequency states
     * @return the immutable batch
     */
    public static FrequencyStateBatch fromStates(FrequencyState[] states) {
        Objects.requireNonNull(states, "states must not be null");
        int length = states.length;
        double[] a = new double[length];
        double[] f = new double[length];
        double[] p = new double[length];

        for (int i = 0; i < length; i++) {
            FrequencyState state = Objects.requireNonNull(states[i], "state at index " + i + " must not be null");
            a[i] = state.amplitude();
            f[i] = state.frequency();
            p[i] = state.phase();
        }
        return new FrequencyStateBatch(a, f, p, length, true);
    }

    /**
     * Creates a batch from a list of {@link FrequencyState} instances in linear time.
     *
     * @param states list of frequency states
     * @return the immutable batch
     */
    public static FrequencyStateBatch fromStates(List<FrequencyState> states) {
        Objects.requireNonNull(states, "states must not be null");
        int length = states.size();
        double[] a = new double[length];
        double[] f = new double[length];
        double[] p = new double[length];

        int i = 0;
        for (FrequencyState state : states) {
            Objects.requireNonNull(state, "state at index " + i + " must not be null");
            a[i] = state.amplitude();
            f[i] = state.frequency();
            p[i] = state.phase();
            i++;
        }
        return new FrequencyStateBatch(a, f, p, length, true);
    }

    /** Returns the number of frequency states in this batch. */
    public int size() {
        return size;
    }

    /** Returns the amplitude at the given index. */
    public double amplitude(int index) {
        checkIndex(index);
        return amplitudes[index];
    }

    /** Returns the frequency at the given index. */
    public double frequency(int index) {
        checkIndex(index);
        return frequencies[index];
    }

    /** Returns the phase at the given index. */
    public double phase(int index) {
        checkIndex(index);
        return phases[index];
    }

    /** Returns a {@link FrequencyState} view for the given index. */
    public FrequencyState get(int index) {
        checkIndex(index);
        return new FrequencyState(amplitudes[index], frequencies[index], phases[index]);
    }

    /** Returns a defensive copy of the amplitudes array. */
    public double[] amplitudes() {
        return Arrays.copyOf(amplitudes, size);
    }

    /** Returns a defensive copy of the frequencies array. */
    public double[] frequencies() {
        return Arrays.copyOf(frequencies, size);
    }

    /** Returns a defensive copy of the phases array. */
    public double[] phases() {
        return Arrays.copyOf(phases, size);
    }

    // Direct package-private array access for zero-overhead internal evaluator loops
    double[] rawAmplitudes() {
        return amplitudes;
    }

    double[] rawFrequencies() {
        return frequencies;
    }

    double[] rawPhases() {
        return phases;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + size);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FrequencyStateBatch other)) return false;
        return size == other.size
                && Arrays.equals(amplitudes, other.amplitudes)
                && Arrays.equals(frequencies, other.frequencies)
                && Arrays.equals(phases, other.phases);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(size);
        result = 31 * result + Arrays.hashCode(amplitudes);
        result = 31 * result + Arrays.hashCode(frequencies);
        result = 31 * result + Arrays.hashCode(phases);
        return result;
    }

    @Override
    public String toString() {
        return "FrequencyStateBatch[size=" + size + "]";
    }
}
