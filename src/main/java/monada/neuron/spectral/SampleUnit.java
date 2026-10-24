package monada.neuron.spectral;

/**
 * Declares whether the host's samples were taken against a physical clock.
 *
 * <p>There is no default: the host owns the meaning of its samples and must declare the unit.
 */
public enum SampleUnit {

    /** Physical sampling: the sample rate is in samples per second and DFT locators are physical hertz. */
    HERTZ,

    /**
     * Abstract sequence with no physical clock: the sample rate must be exactly {@code 1} (one sample per index) and
     * DFT locators are abstract frequencies in cycles per index, never hertz.
     */
    ABSTRACT_PER_INDEX
}
