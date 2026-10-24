package monada.neuron.spectral;

/** Unit of a {@link SpectralPattern} component locator, derived from the basis and the sample unit. */
public enum LocatorUnit {

    /** Physical frequency in hertz: a DFT pattern of samples declared {@link SampleUnit#HERTZ}. */
    HERTZ,

    /** Abstract frequency in cycles per index: a DFT pattern of samples declared {@link SampleUnit#ABSTRACT_PER_INDEX}. */
    CYCLES_PER_INDEX,

    /** Start position (sample index) of a Haar coefficient's support; never a frequency. */
    SAMPLE_POSITION
}
