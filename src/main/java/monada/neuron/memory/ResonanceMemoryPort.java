package monada.neuron.memory;

/**
 * Neuron-owned boundary for recalling long-term associative memory.
 *
 * <p>Implementations own transport, latency, persistence, encoding, and ranking details. Expected
 * availability and timeout outcomes are returned through {@link ResonanceMemoryResponse}; an
 * unexpected runtime failure remains an operational failure of the calling cognitive stage.
 */
public interface ResonanceMemoryPort {

    /** Recalls an ordered, bounded set of resonance matches for one cognitive request. */
    ResonanceMemoryResponse recall(ResonanceMemoryRequest request);
}
