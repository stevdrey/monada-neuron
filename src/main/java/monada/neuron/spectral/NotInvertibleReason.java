package monada.neuron.spectral;

/** Why a {@link WaveSynthesizer} could not invert a valid {@link SpectralPattern} (ADR 0042). */
public enum NotInvertibleReason {

    /**
     * A Haar pattern lacks at least one coefficient of its final approximation (level {@code 0}). Dropped details are
     * a lossy refinement that synthesizes as zero, but the approximation carries the coarse content every detail
     * level refines; without it the reference does not guess a value.
     */
    MISSING_APPROXIMATION
}
