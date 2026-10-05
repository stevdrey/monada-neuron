package monada.neuron.context;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, request-scoped correlation view of the host execution that owns one cognitive cycle.
 *
 * <p>It lets an external capability associate a Neuron action with the originating host execution
 * without putting identity on {@code Signal} (ADR 0005). It lives for exactly one cycle: the host
 * supplies it with the cycle input, the cycle hands it explicitly to the stages that call external
 * capabilities, and Neuron retains it nowhere else. It is not part of the cycle result, trace,
 * Signal equality, resonance semantics, or memory requests. Neuron does not inspect either reference;
 * resolving them, including detecting an expired or unknown one, is the host's responsibility.
 *
 * @param executionRef stable reference of the host execution this cycle belongs to
 * @param lookupRef optional host-owned token the host resolves to its own heavy domain context
 */
public record HostExecutionContext(HostReference executionRef, Optional<HostReference> lookupRef) {

    /** Requires an execution reference and an explicit, non-null optional lookup reference. */
    public HostExecutionContext {
        Objects.requireNonNull(executionRef, "executionRef must not be null");
        Objects.requireNonNull(lookupRef, "lookupRef must not be null");
    }

    /** Creates a context that carries only the execution reference. */
    public static HostExecutionContext of(HostReference executionRef) {
        return new HostExecutionContext(executionRef, Optional.empty());
    }

    /** Returns this context with a host-owned lookup token. */
    public HostExecutionContext withLookupRef(HostReference lookupRef) {
        return new HostExecutionContext(
                executionRef,
                Optional.of(Objects.requireNonNull(lookupRef, "lookupRef must not be null")));
    }
}
