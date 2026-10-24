package monada.neuron.spectral;

/**
 * Host-encoder seam: turns a host-owned source into one explicit {@link SampledWave} window (contract
 * {@code ncm-wave-cognition/1} sections 3.3 and 4.3, ADR 0036).
 *
 * <p>The host implements this interface; Neuron ships no implementation. The encoder owns what the samples mean, the
 * sample rate, the unit, the window and any pre-filtering (in particular the anti-aliasing filter: content above the
 * Nyquist limit cannot be detected from the samples), and it declares its identity, version and source in the
 * {@link WaveProvenance}. Neuron never infers samples or meaning from a {@link monada.neuron.signal.Signal} or from
 * arbitrary text.
 *
 * @param <S> host source type
 */
@FunctionalInterface
public interface WaveEncoder<S> {

    /**
     * Encodes one window of the source.
     *
     * @param source host source
     * @return the explicit samples with their sampling metadata and provenance
     */
    SampledWave encode(S source);
}
