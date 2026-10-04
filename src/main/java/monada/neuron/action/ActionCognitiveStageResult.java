package monada.neuron.action;

import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Observable action-stage result retaining its typed outcome and admitted observation prefix.
 *
 * <p>{@link #outcome()} keeps the status the capability reported, whatever the cycle budget admitted.
 * The cycle-specific facts live here: how many observations the capability produced and whether the
 * cycle admitted all of them.
 *
 * @param outcome the request and the capability result, holding only the admitted observations
 * @param producedObservationCount observations the capability produced before cycle admission
 * @param observationAdmission whether the cycle admitted every produced observation
 */
public record ActionCognitiveStageResult(
        ActionOutcome outcome,
        int producedObservationCount,
        ObservationAdmission observationAdmission) implements CognitiveStageResult {

    /**
     * Requires a typed outcome and counters that agree with it and with the admission flag.
     *
     * @throws IllegalArgumentException if fewer observations were produced than admitted, more than the
     *     request limit, or the admission flag disagrees with the counters
     */
    public ActionCognitiveStageResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(observationAdmission, "observationAdmission must not be null");
        var admitted = outcome.result().observations().size();
        if (producedObservationCount < admitted) {
            throw new IllegalArgumentException(
                    "producedObservationCount must not be below the admitted count: "
                            + producedObservationCount + " < " + admitted);
        }
        if (producedObservationCount > outcome.request().maxObservations()) {
            throw new IllegalArgumentException(
                    "producedObservationCount must not exceed the request limit: "
                            + producedObservationCount + " > " + outcome.request().maxObservations());
        }
        var truncated = producedObservationCount > admitted;
        if (truncated != (observationAdmission == ObservationAdmission.TRUNCATED)) {
            throw new IllegalArgumentException(
                    "observationAdmission " + observationAdmission + " disagrees with produced "
                            + producedObservationCount + " and admitted " + admitted + " observations");
        }
    }

    /** Creates a result whose observations were all admitted. */
    public ActionCognitiveStageResult(ActionOutcome outcome) {
        this(outcome, Objects.requireNonNull(outcome, "outcome must not be null").result().observations().size(),
                ObservationAdmission.COMPLETE);
    }

    /** Returns how many of the produced observations the cycle admitted. */
    public int admittedObservationCount() {
        return outcome.result().observations().size();
    }

    /** Returns the fixed action position in the canonical cycle. */
    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.ACTION;
    }

    /** Expected action outcomes complete the stage without a local execution limit. */
    @Override
    public CognitiveStageStatus status() {
        return CognitiveStageStatus.COMPLETED;
    }

    /** Returns action observations in capability-defined deterministic order. */
    @Override
    public List<Signal> outputSignals() {
        return outcome.result().observations();
    }

    /**
     * Keeps the capability-reported status and the produced count while dropping every observation the
     * cycle budget rejected, and records whether that truncated the output.
     */
    @Override
    public ActionCognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        var admitted = outcome.withAdmittedObservationPrefix(admittedOutputSignals);
        var truncated = producedObservationCount > admitted.result().observations().size();
        return new ActionCognitiveStageResult(
                admitted,
                producedObservationCount,
                truncated ? ObservationAdmission.TRUNCATED : ObservationAdmission.COMPLETE);
    }
}
