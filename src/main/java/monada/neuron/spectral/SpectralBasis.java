package monada.neuron.spectral;

/** Transform family whose components a {@link SpectralPattern} holds. */
public enum SpectralBasis {

    /** Single-sided discrete Fourier transform: one component per frequency bin, all at level {@code 0}. */
    DFT,

    /**
     * Orthonormal multilevel Haar wavelet: level {@code 0} holds the final approximation and levels {@code 1..L}
     * hold the detail coefficients of each decomposition level. Levels are scales and locators are positions.
     */
    HAAR
}
