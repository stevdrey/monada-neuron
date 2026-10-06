package monada.neuron.perception;

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
}
