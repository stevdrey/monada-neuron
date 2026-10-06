package monada.neuron.perception;

import monada.neuron.action.ObservationAdmission;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Observable perception-stage result retaining its typed outcome and admitted signal prefix.
 *
 * <p>{@link #outcome()} keeps the status the adapter reported, whatever the cycle budget admitted. The
 * cycle-specific facts live here: how many signals the adapter produced and whether the cycle admitted
 * all of them. {@code PerceptionStatus.PARTIALLY_COMPLETED} and {@code ObservationAdmission.TRUNCATED}
 * are independent: the first describes the adapter's observation, the second the cycle's capacity.
 *
 * @param outcome the request and the adapter result, holding only the admitted signals
 * @param producedSignalCount signals the adapter produced before cycle admission
 * @param signalAdmission whether the cycle admitted every produced signal
 */
public record PerceptionCognitiveStageResult(
        PerceptionOutcome outcome,
        int producedSignalCount,
        ObservationAdmission signalAdmission) implements CognitiveStageResult {

    /**
     * Requires a typed outcome and counters that agree with it and with the admission flag.
     *
     * @throws IllegalArgumentException if fewer signals were produced than admitted, more than the request
     *     limit, or the admission flag disagrees with the counters
     */
    public PerceptionCognitiveStageResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(signalAdmission, "signalAdmission must not be null");
        var admitted = outcome.result().signals().size();
        if (producedSignalCount < admitted) {
            throw new IllegalArgumentException(
                    "producedSignalCount must not be below the admitted count: "
                            + producedSignalCount + " < " + admitted);
        }
        if (producedSignalCount > outcome.request().maxSignals()) {
            throw new IllegalArgumentException(
                    "producedSignalCount must not exceed the request limit: "
                            + producedSignalCount + " > " + outcome.request().maxSignals());
        }
        var truncated = producedSignalCount > admitted;
        if (truncated != (signalAdmission == ObservationAdmission.TRUNCATED)) {
            throw new IllegalArgumentException(
                    "signalAdmission " + signalAdmission + " disagrees with produced "
                            + producedSignalCount + " and admitted " + admitted + " signals");
        }
    }

    /** Creates a result whose signals were all admitted. */
    public PerceptionCognitiveStageResult(PerceptionOutcome outcome) {
        this(outcome, Objects.requireNonNull(outcome, "outcome must not be null").result().signals().size(),
                ObservationAdmission.COMPLETE);
    }

    /** Returns how many of the produced signals the cycle admitted. */
    public int admittedSignalCount() {
        return outcome.result().signals().size();
    }

    /** Returns the fixed perception position in the canonical cycle. */
    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.PERCEPTION;
    }

    /** Expected perception outcomes complete the stage without a local execution limit. */
    @Override
    public CognitiveStageStatus status() {
        return CognitiveStageStatus.COMPLETED;
    }

    /** Returns the observations in adapter-defined deterministic order. */
    @Override
    public List<Signal> outputSignals() {
        return outcome.result().signals();
    }

    /**
     * Keeps the adapter-reported status and the produced count while dropping every signal the cycle
     * budget rejected, and records whether that truncated the output.
     */
    @Override
    public PerceptionCognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        var admitted = outcome.withAdmittedSignalPrefix(admittedOutputSignals);
        var truncated = producedSignalCount > admitted.result().signals().size();
        return new PerceptionCognitiveStageResult(
                admitted,
                producedSignalCount,
                truncated ? ObservationAdmission.TRUNCATED : ObservationAdmission.COMPLETE);
    }
}
