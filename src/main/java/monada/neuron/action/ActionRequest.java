package monada.neuron.action;

import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/** Immutable action request expressed only in ordered Neuron signal semantics. */
public record ActionRequest(List<Signal> inputSignals, int maxObservations) {

    /** Requires at least one input Signal and an explicit positive observation limit. */
    public ActionRequest {
        inputSignals = List.copyOf(Objects.requireNonNull(inputSignals, "inputSignals must not be null"));
        if (inputSignals.isEmpty()) {
            throw new IllegalArgumentException("inputSignals must not be empty");
        }
        if (maxObservations <= 0) {
            throw new IllegalArgumentException("maxObservations must be positive, got: " + maxObservations);
        }
    }
}
