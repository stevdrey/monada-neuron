package monada.neuron.resonance;

/**
 * Why two spectral patterns cannot be compared (contract {@code ncm-wave-cognition/1} section 7.2).
 *
 * <p>{@link SpectralResonanceDynamics} checks the fields in declaration order and reports the first mismatch, so the
 * reason for a given pair is deterministic.
 */
public enum IncompatibilityReason {

    /** The pattern schema versions differ. */
    VERSION,

    /** One pattern is a DFT spectrum and the other a Haar decomposition. */
    BASIS,

    /** The decomposition depths differ. */
    SCALE,

    /** The windows have different sample counts. */
    SAMPLE_COUNT,

    /** The windows were sampled at different rates; Neuron never resamples. */
    SAMPLE_RATE,

    /** The windows declare different sample units. */
    UNIT
}
