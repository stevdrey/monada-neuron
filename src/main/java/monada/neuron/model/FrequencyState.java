package monada.neuron.model;

/**
 * Represents the internal wave/frequency state of a Node.
 *
 * <p>Modeled as a simple sinusoidal wave: f(t) = amplitude * sin(2π * frequency * t + phase).
 * This structure is intentionally minimal for Phase 1; future phases may extend it to support
 * multi-dimensional harmonics or composite waveforms.
 *
 * <p>Immutable by design — state transitions produce new instances, enabling safe history tracking
 * and concurrent reads without synchronization.
 *
 * @param amplitude  The peak magnitude of the wave (non-negative). Represents signal strength.
 * @param frequency  The number of oscillations per unit time (non-negative, in Hz or abstract units).
 * @param phase      The finite initial phase offset in radians. Range: [0, 2π), though not enforced.
 */
public record FrequencyState(double amplitude, double frequency, double phase) {

    /**
     * Canonical constructor with basic validation.
     * Amplitude and frequency must be non-negative and finite; phase must be finite.
     */
    public FrequencyState {
        if (!Double.isFinite(amplitude)) {
            throw new IllegalArgumentException("amplitude must be finite, got: " + amplitude);
        }
        if (amplitude < 0) {
            throw new IllegalArgumentException("amplitude must be non-negative, got: " + amplitude);
        }
        if (!Double.isFinite(frequency)) {
            throw new IllegalArgumentException("frequency must be finite, got: " + frequency);
        }
        if (frequency < 0) {
            throw new IllegalArgumentException("frequency must be non-negative, got: " + frequency);
        }
        if (!Double.isFinite(phase)) {
            throw new IllegalArgumentException("phase must be finite, got: " + phase);
        }
    }

    /** A zero / silent state — no amplitude, no frequency, zero phase. */
    public static final FrequencyState ZERO = new FrequencyState(0.0, 0.0, 0.0);

    /**
     * Returns a new {@code FrequencyState} with the amplitude scaled by the given factor.
     * Useful for attenuation or amplification without altering frequency or phase.
     *
     * @param factor finite scaling factor (must be non-negative)
     * @return scaled state
     */
    public FrequencyState withScaledAmplitude(double factor) {
        if (!Double.isFinite(factor)) {
            throw new IllegalArgumentException("factor must be finite, got: " + factor);
        }
        if (factor < 0) {
            throw new IllegalArgumentException("factor must be non-negative, got: " + factor);
        }
        return new FrequencyState(amplitude * factor, frequency, phase);
    }

    /**
     * Returns a new {@code FrequencyState} with the phase shifted by the given offset (in radians).
     *
     * @param offset finite phase delta in radians
     * @return phase-shifted state
     */
    public FrequencyState withPhaseShift(double offset) {
        if (!Double.isFinite(offset)) {
            throw new IllegalArgumentException("offset must be finite, got: " + offset);
        }
        return new FrequencyState(amplitude, frequency, phase + offset);
    }
}
