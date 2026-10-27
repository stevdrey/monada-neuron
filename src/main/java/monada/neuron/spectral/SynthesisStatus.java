package monada.neuron.spectral;

/**
 * Reconstruction fidelity class of one {@link WaveSynthesizer} result (contract {@code ncm-wave-cognition/1} section 9,
 * ADR 0042). It is decided structurally from the pattern, never from a measured error.
 */
public enum SynthesisStatus {

    /**
     * The pattern is complete (every key of its frame present), so the reference inverse reproduces the decomposed
     * samples within the round-trip tolerance of contract section 12.4.
     */
    EXACT,

    /**
     * Components were dropped (top-K, thresholding); they are synthesized as zero. The samples are a reconstruction of
     * the retained components only, never claimed to be the decomposed samples.
     */
    LOSSY,

    /** The pattern lacks information the reference inverse needs; no samples are produced. */
    NOT_INVERTIBLE
}
