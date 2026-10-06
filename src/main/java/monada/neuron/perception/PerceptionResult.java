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

    /**
     * Returns this result with only the cycle-admitted ordered signal prefix retained.
     *
     * <p>The status is the adapter's report and is never changed by admission: a cycle budget limits what
     * the cycle keeps, not what the adapter observed.
     *
     * @throws IllegalArgumentException when the argument is not an ordered prefix of {@link #signals()}
     */
    public PerceptionResult withAdmittedSignalPrefix(List<Signal> admittedSignals) {
        var stable = List.copyOf(Objects.requireNonNull(admittedSignals, "admittedSignals must not be null"));
        if (stable.size() > signals.size()) {
            throw new IllegalArgumentException("admittedSignals cannot exceed original signal count");
        }
        for (var index = 0; index < stable.size(); index++) {
            if (!signals.get(index).equals(stable.get(index))) {
                throw new IllegalArgumentException(
                        "admittedSignals must be an ordered prefix of the original signals");
            }
        }
        if (stable.size() == signals.size()) {
            return this;
        }
        return new PerceptionResult(status, signalLimit, stable);
    }
}
