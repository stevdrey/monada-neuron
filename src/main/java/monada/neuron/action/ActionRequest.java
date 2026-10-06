package monada.neuron.action;

import monada.neuron.context.HostExecutionContext;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable action request expressed in ordered Neuron signal semantics plus, optionally, the opaque
 * host execution it belongs to.
 *
 * <p>An adapter resolves {@code hostContext} on the host side to associate the action with its
 * originating execution. When it is absent, or the adapter cannot resolve it (missing, expired, or
 * unknown), the adapter reports an expected outcome such as {@link ActionStatus#REJECTED} rather than
 * throwing; Neuron never interprets the references.
 *
 * @param inputSignals ordered, non-empty input signals
 * @param maxObservations positive observation limit
 * @param hostContext request-scoped host correlation, or empty when the cycle has none
 */
public record ActionRequest(
        List<Signal> inputSignals,
        int maxObservations,
        Optional<HostExecutionContext> hostContext) {

    /** Requires at least one input Signal and an explicit positive observation limit. */
    public ActionRequest {
        inputSignals = List.copyOf(Objects.requireNonNull(inputSignals, "inputSignals must not be null"));
        if (inputSignals.isEmpty()) {
            throw new IllegalArgumentException("inputSignals must not be empty");
        }
        if (maxObservations <= 0) {
            throw new IllegalArgumentException("maxObservations must be positive, got: " + maxObservations);
        }
        Objects.requireNonNull(hostContext, "hostContext must not be null");
    }

    /** Creates a request without host context. */
    public ActionRequest(List<Signal> inputSignals, int maxObservations) {
        this(inputSignals, maxObservations, Optional.empty());
    }
}
