package monada.neuron.perception;

import monada.neuron.action.ObservationAdmission;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Observable perception-stage result retaining the adapter's outcome and, separately, the signals the
 * cycle admitted.
 *
 * <p>{@link #outcome()} is the immutable truth of what the adapter reported, including its status and
 * every signal it produced; the cycle budget never rewrites it. The cycle-specific facts live here: how
 * many signals the adapter produced, which ordered prefix the cycle admitted (possibly none), and whether
 * that truncated the output. {@code PerceptionStatus.PARTIALLY_COMPLETED} and
 * {@code ObservationAdmission.TRUNCATED} are independent: the first describes the adapter's observation,
 * the second the cycle's capacity.
 *
 * @param outcome the request and the unmodified adapter result
 * @param producedSignalCount signals the adapter produced before cycle admission
 * @param admittedSignals the ordered prefix of the produced signals the cycle admitted
 * @param signalAdmission whether the cycle admitted every produced signal
 */
public record PerceptionCognitiveStageResult(
        PerceptionOutcome outcome,
        int producedSignalCount,
        List<Signal> admittedSignals,
        ObservationAdmission signalAdmission) implements CognitiveStageResult {

    /**
     * Requires a typed outcome, an admitted ordered prefix of its signals, and an admission flag that
     * agrees with the counters.
     *
     * @throws IllegalArgumentException if the produced count disagrees with the outcome, the admitted
     *     signals are not an ordered prefix of the produced ones, or the admission flag disagrees with them
     */
    public PerceptionCognitiveStageResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(signalAdmission, "signalAdmission must not be null");
        admittedSignals = List.copyOf(Objects.requireNonNull(admittedSignals, "admittedSignals must not be null"));
        var produced = outcome.result().signals();
        if (producedSignalCount != produced.size()) {
            throw new IllegalArgumentException(
                    "producedSignalCount must match the outcome's signals: "
                            + producedSignalCount + " != " + produced.size());
        }
        if (admittedSignals.size() > produced.size()) {
            throw new IllegalArgumentException(
                    "admittedSignals cannot exceed the produced count: "
                            + admittedSignals.size() + " > " + produced.size());
        }
        for (var index = 0; index < admittedSignals.size(); index++) {
            if (!produced.get(index).equals(admittedSignals.get(index))) {
                throw new IllegalArgumentException(
                        "admittedSignals must be an ordered prefix of the produced signals");
            }
        }
        var truncated = admittedSignals.size() < producedSignalCount;
        if (truncated != (signalAdmission == ObservationAdmission.TRUNCATED)) {
            throw new IllegalArgumentException(
                    "signalAdmission " + signalAdmission + " disagrees with produced "
                            + producedSignalCount + " and admitted " + admittedSignals.size() + " signals");
        }
    }

    /** Creates a result whose signals were all admitted. */
    public PerceptionCognitiveStageResult(PerceptionOutcome outcome) {
        this(outcome,
                Objects.requireNonNull(outcome, "outcome must not be null").result().signals().size(),
                outcome.result().signals(),
                ObservationAdmission.COMPLETE);
    }

    /** Returns how many of the produced signals the cycle admitted. */
    public int admittedSignalCount() {
        return admittedSignals.size();
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
        return admittedSignals;
    }

    /**
     * Keeps the outcome and the produced count unchanged, stores the admitted prefix separately, and
     * records whether that truncated the output. Admitting no signal is representable.
     */
    @Override
    public PerceptionCognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        var admitted = List.copyOf(Objects.requireNonNull(
                admittedOutputSignals, "admittedOutputSignals must not be null"));
        return new PerceptionCognitiveStageResult(
                outcome,
                producedSignalCount,
                admitted,
                admitted.size() < producedSignalCount
                        ? ObservationAdmission.TRUNCATED
                        : ObservationAdmission.COMPLETE);
    }
}
