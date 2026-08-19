package monada.neuron.action;

import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/** Immutable action outcome that keeps one admitted request associated with its observed result. */
public record ActionOutcome(ActionRequest request, ActionResult result) {

    /** Requires the result to acknowledge the request's explicit observation limit. */
    public ActionOutcome {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (result.observationLimit() != request.maxObservations()) {
            throw new IllegalArgumentException("result observationLimit must match request maxObservations");
        }
    }

    /** Returns this outcome with only the cycle-admitted observation prefix retained. */
    public ActionOutcome withAdmittedObservationPrefix(List<Signal> admittedObservations) {
        return new ActionOutcome(request, result.withAdmittedObservationPrefix(admittedObservations));
    }
}
