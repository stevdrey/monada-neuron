package monada.neuron.perception;

import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.List;
import java.util.Objects;

/** Immutable bounded result whose signal order is supplied by the perception adapter. */
public record PerceptionResult(
        PerceptionStatus status,
        int signalLimit,
        List<Signal> signals) {

    /** Validates the explicit bound, the OBSERVATION kind, and the per-status signal rule. */
    public PerceptionResult {
        Objects.requireNonNull(status, "status must not be null");
        if (signalLimit <= 0) {
            throw new IllegalArgumentException("signalLimit must be positive, got: " + signalLimit);
        }
        signals = List.copyOf(Objects.requireNonNull(signals, "signals must not be null"));
        if (signals.size() > signalLimit) {
            throw new IllegalArgumentException(
                    "signals must not exceed signalLimit: " + signals.size() + " > " + signalLimit);
        }
        for (var signal : signals) {
            if (signal.kind() != SignalKind.OBSERVATION) {
                throw new IllegalArgumentException(
                        "perception signals must be OBSERVATION, got: " + signal.kind());
            }
        }
        var requiresSignals = switch (status) {
            case SUCCEEDED, PARTIALLY_COMPLETED -> true;
            case EMPTY, REJECTED, UNAVAILABLE, TIMED_OUT, FAILED -> false;
        };
        if (requiresSignals && signals.isEmpty()) {
            throw new IllegalArgumentException(status + " results must contain signals");
        }
        if (!requiresSignals && !signals.isEmpty()) {
            throw new IllegalArgumentException(status + " results must not contain signals");
        }
    }
}
