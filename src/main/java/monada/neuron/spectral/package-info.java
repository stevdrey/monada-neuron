/**
 * Experimental, opt-in spectral value types of the NCM wave cognition contract ({@code ncm-wave-cognition/1},
 * {@code docs/specs/ncm-wave-cognition-v1.md}, ADR 0034 to ADR 0036, ADR 0040 and ADR 0042).
 *
 * <p>A {@link monada.neuron.spectral.SpectralPattern} is bounded, immutable, cycle-local data: the components a
 * decomposer found in one window of host samples, with the basis, scale, sampling metadata and provenance needed to
 * interpret them. It is not a {@link monada.neuron.signal.Signal}, does not extend
 * {@link monada.neuron.model.FrequencyState}, carries no concept label and is never persisted by Neuron.
 *
 * <p><b>Units.</b> A component locator is a <em>physical</em> frequency in hertz only for a DFT pattern whose input
 * declared {@link monada.neuron.spectral.SampleUnit#HERTZ}. For {@link monada.neuron.spectral.SampleUnit#ABSTRACT_PER_INDEX}
 * it is an <em>abstract</em> frequency in cycles per index (sample). A Haar locator is a sample position and a Haar
 * level is a dyadic scale; neither is ever a frequency. {@link monada.neuron.spectral.LocatorUnit} names the unit of
 * each pattern's locators explicitly.
 *
 * <p><b>Decomposition.</b> A host-implemented {@link monada.neuron.spectral.WaveEncoder} supplies one explicit
 * {@link monada.neuron.spectral.SampledWave} window with its sampling metadata and provenance; a
 * {@link monada.neuron.spectral.WaveDecomposer} such as the scalar reference
 * {@link monada.neuron.spectral.DftWaveDecomposer} turns it into a pattern (ADR 0036). The opt-in
 * {@link monada.neuron.spectral.HaarWaveDecomposer} is the separate multiresolution strategy (ADR 0040): fixed depth,
 * power-of-two windows only, coefficients by scale and position with
 * {@link monada.neuron.spectral.SpectralPattern#levelEnergy(int) scale-local energy}. Fourier and wavelet patterns
 * never share a frame, so they cannot be compared as if their components were the same bins. No public type here
 * accepts a {@code Signal}: samples are never derived from one.
 *
 * <p><b>Synthesis.</b> The scalar reference {@link monada.neuron.spectral.WaveSynthesizer} inverts a pattern into
 * samples (ADR 0042): {@link monada.neuron.spectral.SynthesisStatus#EXACT} only for a complete pattern,
 * {@link monada.neuron.spectral.SynthesisStatus#LOSSY} when components were dropped, and a typed
 * {@link monada.neuron.spectral.SynthesisResult.NotInvertible} for a Haar pattern without its final approximation.
 * It reports the retained-energy fraction and, when the original wave is supplied, the reconstruction errors. It
 * reconstructs samples only, never meaning.
 *
 * <p>Construction errors, including a malformed {@code SampledWave}, are caller bugs and throw
 * {@link java.lang.IllegalArgumentException} or {@link java.lang.NullPointerException}; a valid wave outside a
 * transform limit is a typed {@link monada.neuron.spectral.DecompositionResult.DecompositionRejected}. Nothing in this
 * package depends on a codec, the Resonance Store or hardware-specific code, and every transform is a single-threaded
 * {@code StrictMath} reference.
 */
package monada.neuron.spectral;
