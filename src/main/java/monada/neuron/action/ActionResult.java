package monada.neuron.action;

import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/** Immutable bounded result whose observation order is supplied by the action capability. */
public record ActionResult(
        ActionStatus status,
        int observationLimit,
        List<Signal> observations) {

    /** Validates the explicit bound and expected non-success observation semantics. */
    public ActionResult {
        Objects.requireNonNull(status, "status must not be null");
        if (observationLimit <= 0) {
            throw new IllegalArgumentException("observationLimit must be positive, got: " + observationLimit);
        }
        observations = List.copyOf(Objects.requireNonNull(observations, "observations must not be null"));
        if (observations.size() > observationLimit) {
            throw new IllegalArgumentException(
                    "observations must not exceed observationLimit: "
                            + observations.size() + " > " + observationLimit);
        }
        if (switch (status) {
            case REJECTED, UNAVAILABLE, TIMED_OUT, FAILED -> !observations.isEmpty();
            case SUCCEEDED, PARTIALLY_COMPLETED -> false;
        }) {
            throw new IllegalArgumentException(status + " results must not contain observations");
        }
    }

    /**
     * Returns this result with only the cycle-admitted ordered observation prefix retained.
     *
     * <p>The status is the capability's report and is never changed by admission: a cycle budget limits
     * what the cycle keeps, not what the action achieved. {@link ActionCognitiveStageResult} records the
     * truncation separately.
     */
    public ActionResult withAdmittedObservationPrefix(List<Signal> admittedObservations) {
        var stableObservations = List.copyOf(Objects.requireNonNull(
                admittedObservations,
                "admittedObservations must not be null"));
        if (stableObservations.size() > observations.size()) {
            throw new IllegalArgumentException("admittedObservations cannot exceed original observation count");
        }
        for (var index = 0; index < stableObservations.size(); index++) {
            if (!observations.get(index).equals(stableObservations.get(index))) {
                throw new IllegalArgumentException(
                        "admittedObservations must be an ordered prefix of the original observations");
            }
        }
        if (stableObservations.size() == observations.size()) {
            return this;
        }
        return new ActionResult(status, observationLimit, stableObservations);
    }
}
