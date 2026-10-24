package monada.neuron.spectral;

/**
 * The prism: decomposes one explicit {@link SampledWave} into a {@link SpectralPattern} (contract
 * {@code ncm-wave-cognition/1} section 2, ADR 0036).
 *
 * <p>Implementations are pure and deterministic: the same wave gives an equal result, they keep no state between
 * calls and are safe to share between threads. A transform limit the wave does not meet is an expected value, a
 * {@link DecompositionResult.DecompositionRejected} with a typed reason, never an exception and never a repaired or
 * padded input.
 */
public interface WaveDecomposer {

    /** Returns the transform family of every pattern this decomposer produces. */
    SpectralBasis basis();

    /**
     * Decomposes one window.
     *
     * @param wave explicit samples, sampling metadata and provenance
     * @return the pattern, or a typed rejection
     */
    DecompositionResult decompose(SampledWave wave);
}
