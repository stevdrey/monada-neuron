package monada.neuron.resonance.adapter;

/** Adapter-owned failure raised while opening or configuring the Resonance Store binding. */
public final class ResonanceStoreAdapterException extends RuntimeException {

    /** Wraps the store-specific cause so store exception types do not reach callers. */
    public ResonanceStoreAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
