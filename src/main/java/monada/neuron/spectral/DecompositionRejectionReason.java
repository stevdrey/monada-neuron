package monada.neuron.spectral;

/** Why a {@link WaveDecomposer} rejected a structurally valid {@link SampledWave} (ADR 0036). */
public enum DecompositionRejectionReason {

    /**
     * The source energy {@code Σ x_n²} is outside the range the pattern can represent: above
     * {@link SpectralPattern#MAX_SOURCE_ENERGY} ({@code Double.MAX_VALUE / 2}, headroom so the recomputed retained
     * energy cannot overflow), or nonzero but below {@link Double#MIN_NORMAL}, where the Parseval tolerance of contract
     * section 12.4 can no longer be checked. Silent samples (energy exactly {@code 0}) are accepted.
     */
    ENERGY_OUT_OF_RANGE,

    /**
     * The sample rate is outside the range the basis can locate: for a DFT, the highest locator
     * {@code floor(N/2) * sampleRate} overflows, or the bin spacing {@code sampleRate / N} is subnormal, so distinct
     * bins would share a locator.
     */
    SAMPLE_RATE_OUT_OF_RANGE
}
