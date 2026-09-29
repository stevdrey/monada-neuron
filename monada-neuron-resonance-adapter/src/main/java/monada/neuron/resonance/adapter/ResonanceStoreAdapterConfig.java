package monada.neuron.resonance.adapter;

import com.monada.api.MonadaMemoryOptions;

import java.util.Objects;

/**
 * Immutable adapter configuration.
 *
 * @param queryEncoder Signal-to-query translation
 * @param signalDecoder recalled-content-to-Signal translation
 * @param memoryOptions store open options; the store validates them against the persisted profile
 * @param threshold minimum store score for a candidate to be returned
 */
public record ResonanceStoreAdapterConfig(
        SignalQueryEncoder queryEncoder,
        RecalledSignalDecoder signalDecoder,
        MonadaMemoryOptions memoryOptions,
        double threshold) {

    /** Validates all required components. */
    public ResonanceStoreAdapterConfig {
        Objects.requireNonNull(queryEncoder, "queryEncoder must not be null");
        Objects.requireNonNull(signalDecoder, "signalDecoder must not be null");
        Objects.requireNonNull(memoryOptions, "memoryOptions must not be null");
        if (!Double.isFinite(threshold)) {
            throw new IllegalArgumentException("threshold must be finite, got: " + threshold);
        }
    }

    /** Returns deterministic interim codecs, store default options, and no score threshold. */
    public static ResonanceStoreAdapterConfig defaults() {
        return new ResonanceStoreAdapterConfig(
                new CanonicalSignalQueryEncoder(),
                new HashedSignalDecoder(),
                MonadaMemoryOptions.defaults(),
                0.0);
    }
}
