package monada.neuron.reasoning.concept;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.reasoning.HypothesisHandOff;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Typed {@code REASONING} result of {@link WaveCognitionReasoningStage}: the input signals unchanged, the concept
 * hypotheses for {@code EVALUATION} and the execution's {@link WaveCognitionReport}.
 *
 * @param status {@code LIMIT_REACHED} exactly when the report's outcome reached a limit
 * @param outputSignals ordered signals offered to the next stage
 * @param hypotheses immutable concept hypotheses produced this cycle; empty unless windows were examined
 * @param report the execution's diagnostics
 */
public record WaveCognitionStageResult(CognitiveStageStatus status, List<Signal> outputSignals,
                                       HypothesisSet hypotheses, WaveCognitionReport report)
        implements CognitiveStageResult, HypothesisHandOff {

    /** Validates and snapshots components. */
    public WaveCognitionStageResult {
        Objects.requireNonNull(status, "status must not be null");
        outputSignals = List.copyOf(Objects.requireNonNull(outputSignals, "outputSignals must not be null"));
        Objects.requireNonNull(hypotheses, "hypotheses must not be null");
        Objects.requireNonNull(report, "report must not be null");
        var expected = report.outcome().limitReached()
                ? CognitiveStageStatus.LIMIT_REACHED : CognitiveStageStatus.COMPLETED;
        if (status != expected) {
            throw new IllegalArgumentException(report.outcome() + " requires status " + expected);
        }
        if (!report.outcome().examinedWindows() && !hypotheses.isEmpty()) {
            throw new IllegalArgumentException(report.outcome() + " produces no hypothesis");
        }
    }

    @Override
    public CognitiveStageKind kind() {
        return CognitiveStageKind.REASONING;
    }

    /** Keeps hypotheses and report, which are independent of signals, and trims signals to the prefix. */
    @Override
    public CognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        Objects.requireNonNull(admittedOutputSignals, "admittedOutputSignals must not be null");
        if (admittedOutputSignals.size() > outputSignals.size()
                || !outputSignals.subList(0, admittedOutputSignals.size()).equals(admittedOutputSignals)) {
            throw new IllegalArgumentException("admitted output signals must be a prefix of the reasoning outputs");
        }
        return new WaveCognitionStageResult(status, admittedOutputSignals, hypotheses, report);
    }

    @Override
    public boolean retainsTypedHandOff() {
        return !hypotheses.isEmpty();
    }

    /** Rejects signal evidence that references an occurrence the context never accepted. */
    @Override
    public void validateProvenance(CognitiveContext context) {
        Objects.requireNonNull(context, "context must not be null");
        hypotheses.validateSignalProvenance(context.acceptedSignals());
    }
}
