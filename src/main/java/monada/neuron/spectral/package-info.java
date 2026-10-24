/**
 * Experimental, opt-in spectral value types of the NCM wave cognition contract ({@code ncm-wave-cognition/1},
 * {@code docs/specs/ncm-wave-cognition-v1.md}, ADR 0034 and ADR 0035).
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
 * {@link monada.neuron.spectral.DftWaveDecomposer} turns it into a pattern (ADR 0036). No public type here accepts a
 * {@code Signal}: samples are never derived from one.
 *
 * <p>Construction errors, including a malformed {@code SampledWave}, are caller bugs and throw
 * {@link java.lang.IllegalArgumentException} or {@link java.lang.NullPointerException}; a valid wave outside a
 * transform limit is a typed {@link monada.neuron.spectral.DecompositionResult.DecompositionRejected}. Nothing in this
 * package depends on a codec, the Resonance Store or hardware-specific code, and every transform is a single-threaded
 * {@code StrictMath} reference.
 */
package monada.neuron.spectral;
