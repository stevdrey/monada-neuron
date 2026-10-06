package monada.neuron.perception;

import monada.neuron.context.HostExecutionContext;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable request for one bounded host observation.
 *
 * <p>It carries no payload: the adapter resolves the observation on the host side through the opaque
 * {@code hostContext}. When the context is absent, or the adapter cannot resolve it, the adapter reports
 * an expected status such as {@link PerceptionStatus#REJECTED}; Neuron never interprets the references.
 *
 * @param maxSignals positive output limit
 * @param hostContext request-scoped host correlation, or empty when the cycle has none
 */
public record PerceptionRequest(int maxSignals, Optional<HostExecutionContext> hostContext) {

    /** Requires an explicit positive output limit and a non-null optional context. */
    public PerceptionRequest {
        if (maxSignals <= 0) {
            throw new IllegalArgumentException("maxSignals must be positive, got: " + maxSignals);
        }
        Objects.requireNonNull(hostContext, "hostContext must not be null");
    }

    /** Creates a request without host context. */
    public PerceptionRequest(int maxSignals) {
        this(maxSignals, Optional.empty());
    }
}
