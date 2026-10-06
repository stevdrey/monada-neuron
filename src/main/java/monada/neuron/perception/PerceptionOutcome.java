package monada.neuron.perception;

import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/** Immutable outcome that keeps one admitted request associated with its observed result. */
public record PerceptionOutcome(PerceptionRequest request, PerceptionResult result) {

    /** Requires the result to acknowledge the request's explicit signal limit. */
    public PerceptionOutcome {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (result.signalLimit() != request.maxSignals()) {
            throw new IllegalArgumentException("result signalLimit must match request maxSignals");
        }
    }

    /** Returns this outcome with only the cycle-admitted signal prefix retained. */
    public PerceptionOutcome withAdmittedSignalPrefix(List<Signal> admittedSignals) {
        return new PerceptionOutcome(request, result.withAdmittedSignalPrefix(admittedSignals));
    }
}
