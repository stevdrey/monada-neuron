package monada.neuron.runtime.selection;

import java.util.Objects;

/**
 * Thrown when an explicitly requested backend is semantically ineligible for the given workload
 * (for example, missing snapshot, stale topology, or contextual mode constraint)
 * and {@link FallbackPolicy#FAIL_FAST} is active.
 */
public class BackendIneligibleException extends IllegalStateException {

    private final BackendId backendId;

    public BackendIneligibleException(BackendId backendId, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.backendId = Objects.requireNonNull(backendId, "backendId must not be null");
    }

    public BackendIneligibleException(BackendId backendId, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.backendId = Objects.requireNonNull(backendId, "backendId must not be null");
    }

    /** Returns the identifier of the ineligible backend. */
    public BackendId backendId() {
        return backendId;
    }
}
