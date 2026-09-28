package monada.neuron.runtime.selection;

import java.util.Objects;

/**
 * Thrown when an explicitly requested backend is not available in the current runtime environment
 * and {@link FallbackPolicy#FAIL_FAST} is active.
 */
public class BackendUnavailableException extends IllegalStateException {

    private final BackendId backendId;

    public BackendUnavailableException(BackendId backendId, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.backendId = Objects.requireNonNull(backendId, "backendId must not be null");
    }

    public BackendUnavailableException(BackendId backendId, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.backendId = Objects.requireNonNull(backendId, "backendId must not be null");
    }

    /** Returns the identifier of the unavailable backend. */
    public BackendId backendId() {
        return backendId;
    }
}
