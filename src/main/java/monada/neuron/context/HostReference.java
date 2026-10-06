package monada.neuron.context;

import java.util.Objects;

/**
 * Opaque, bounded token that a host uses to refer to something it owns.
 *
 * <p>Neuron never parses, resolves, or compares the meaning of the value; it only carries it from the
 * host that created it to the external capability that must resolve it.
 *
 * @param value non-blank host-defined text of at most {@link #MAX_LENGTH} characters
 */
public record HostReference(String value) {

    /** Upper bound that keeps the token control-plane sized; heavy domain data stays with the host. */
    public static final int MAX_LENGTH = 128;

    /** Requires a non-blank, bounded value. */
    public HostReference {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "value must not exceed " + MAX_LENGTH + " characters, got: " + value.length());
        }
    }
}
