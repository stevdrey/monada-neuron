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
 * <p>Construction errors are caller bugs and throw {@link java.lang.IllegalArgumentException} or
 * {@link java.lang.NullPointerException}; typed rejections of host input belong to the decomposers (#123, #124).
 * Nothing in this package depends on a transform, codec, the Resonance Store or hardware-specific code.
 */
package monada.neuron.spectral;
