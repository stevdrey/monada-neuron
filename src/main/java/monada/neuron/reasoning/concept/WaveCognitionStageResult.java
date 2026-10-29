package monada.neuron.reasoning.concept;

import monada.neuron.context.CognitiveContext;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.reasoning.HypothesisHandOff;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.ReasoningCognitiveStageResult;
import monada.neuron.signal.Signal;

import java.util.List;
import java.util.Objects;

/**
 * Typed {@code REASONING} result of {@link WaveCognitionReasoningStage}: the ordinary reasoning hand-off (signals and
 * concept hypotheses for {@code EVALUATION}) plus the execution's {@link WaveCognitionReport}.
 *
 * <p>Signal admission, typed hand-off and provenance rules are those of {@link ReasoningCognitiveStageResult}, to
 * which this result delegates.
 *
 * @param reasoning the reasoning hand-off; its status is {@code LIMIT_REACHED} exactly when the report's outcome
 *     reached a limit, and its hypotheses are empty unless windows were examined
 * @param report the execution's diagnostics
 */
public record WaveCognitionStageResult(ReasoningCognitiveStageResult reasoning, WaveCognitionReport report)
        implements CognitiveStageResult, HypothesisHandOff {

    /** Validates the agreement of the hand-off with the report. */
    public WaveCognitionStageResult {
        Objects.requireNonNull(reasoning, "reasoning must not be null");
        Objects.requireNonNull(report, "report must not be null");
        var expected = report.outcome().limitReached()
                ? CognitiveStageStatus.LIMIT_REACHED : CognitiveStageStatus.COMPLETED;
        if (reasoning.status() != expected) {
            throw new IllegalArgumentException(report.outcome() + " requires status " + expected);
        }
        if (!report.outcome().examinedWindows() && !reasoning.hypotheses().isEmpty()) {
            throw new IllegalArgumentException(report.outcome() + " produces no hypothesis");
        }
    }

    /** Creates a result from its parts. */
    public WaveCognitionStageResult(CognitiveStageStatus status, List<Signal> outputSignals, HypothesisSet hypotheses,
                                    WaveCognitionReport report) {
        this(new ReasoningCognitiveStageResult(status, outputSignals, hypotheses), report);
    }

    @Override
    public CognitiveStageKind kind() {
        return reasoning.kind();
    }

    @Override
    public CognitiveStageStatus status() {
        return reasoning.status();
    }

    @Override
    public List<Signal> outputSignals() {
        return reasoning.outputSignals();
    }

    @Override
    public HypothesisSet hypotheses() {
        return reasoning.hypotheses();
    }

    /** Keeps the hypotheses and report and trims the signals to the admitted prefix. */
    @Override
    public CognitiveStageResult withAdmittedOutputSignals(List<Signal> admittedOutputSignals) {
        return new WaveCognitionStageResult(
                (ReasoningCognitiveStageResult) reasoning.withAdmittedOutputSignals(admittedOutputSignals), report);
    }

    @Override
    public boolean retainsTypedHandOff() {
        return reasoning.retainsTypedHandOff();
    }

    @Override
    public void validateProvenance(CognitiveContext context) {
        reasoning.validateProvenance(context);
    }
}
