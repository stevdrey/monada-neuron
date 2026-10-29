package monada.neuron.reasoning.concept;

import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.model.FrequencyState;
import monada.neuron.reasoning.Hypothesis;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.Proposition;
import monada.neuron.reasoning.ReasoningCognitiveStageResult;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveCognitionStageResultTest {

    private static final Signal FIRST = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0, 0.0));
    private static final Signal SECOND = new Signal(SignalKind.OBSERVATION, new FrequencyState(2.0, 10.0, 0.0));
    private static final HypothesisSet ONE = new HypothesisSet(
            List.of(new Hypothesis(0, new Proposition(127, 11), List.of())), HypothesisLimits.DEFAULT);
    private static final WindowOutcome PREDICTED = new WindowOutcome.Predicted(0, 2, 1, 0.8, Optional.empty(),
            new PredictionWork(6, 4, 2), Optional.empty());

    private static WaveCognitionReport processed() {
        return new WaveCognitionReport(WaveCognitionOutcome.PROCESSED, 1, List.of(PREDICTED), 1, 0, 6);
    }

    @Test
    void keepsHypothesesAndReportWhenTheCycleAdmitsASignalPrefix() {
        var result = new WaveCognitionStageResult(CognitiveStageStatus.COMPLETED, List.of(FIRST, SECOND), ONE,
                processed());
        var admitted = (WaveCognitionStageResult) result.withAdmittedOutputSignals(List.of(FIRST));

        assertAll(
                () -> assertEquals(CognitiveStageKind.REASONING, result.kind()),
                () -> assertTrue(result.retainsTypedHandOff()),
                () -> assertEquals(List.of(FIRST), admitted.outputSignals()),
                () -> assertEquals(ONE, admitted.hypotheses()),
                () -> assertEquals(result.report(), admitted.report()),
                () -> assertEquals(new ReasoningCognitiveStageResult(CognitiveStageStatus.COMPLETED, List.of(FIRST),
                        ONE), admitted.reasoning()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> result.withAdmittedOutputSignals(List.of(SECOND))));
    }

    @Test
    void statusAndHypothesesMustAgreeWithTheReportedOutcome() {
        var limited = new WaveCognitionReport(WaveCognitionOutcome.WORK_LIMIT_REACHED, 2, List.of(PREDICTED), 1, 0, 6);
        var notRequested = WaveCognitionReport.empty(WaveCognitionOutcome.NOT_REQUESTED, 0);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WaveCognitionStageResult(CognitiveStageStatus.LIMIT_REACHED, List.of(), ONE,
                                processed())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WaveCognitionStageResult(CognitiveStageStatus.COMPLETED, List.of(), ONE, limited)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new WaveCognitionStageResult(CognitiveStageStatus.COMPLETED, List.of(), ONE,
                                notRequested)),
                () -> assertFalse(new WaveCognitionStageResult(CognitiveStageStatus.COMPLETED, List.of(FIRST),
                        HypothesisSet.EMPTY, notRequested).retainsTypedHandOff()));
    }

    @Test
    void reportsValidatePositionsTotalsAndOutcomeShape() {
        var incompatible = new WindowOutcome.Incompatible(0);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.PROCESSED, 1, List.of(new WindowOutcome.Incompatible(1)), 0, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.PROCESSED, 1, List.of(PREDICTED), 1, 0, 5)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.PROCESSED, 2, List.of(PREDICTED), 1, 0, 6)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.REJECTED_OVERSIZED, 3, List.of(PREDICTED), 1, 0, 6)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.REJECTED_OVERSIZED, 0, List.of(), 0, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.NOT_REQUESTED, 1, List.of(), 0, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.WORK_LIMIT_REACHED, 1, List.of(PREDICTED), 1, 0, 6)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.HYPOTHESIS_LIMIT_REACHED, 2, List.of(incompatible), 0, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.HYPOTHESIS_LIMIT_REACHED, 2, List.of(), 0, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.PROCESSED, 1, List.of(PREDICTED), 2, 0, 6)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionReport(
                        WaveCognitionOutcome.PROCESSED, 1, List.of(PREDICTED), 1, 2, 6)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WindowOutcome.Predicted(0, 2, 1, 1.5,
                        Optional.empty(), PredictionWork.NONE, Optional.empty())));
    }

    @Test
    void limitsAreBounded() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionLimits(0, 1,
                        PredictionBudget.DEFAULT, HypothesisLimits.DEFAULT, false)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionLimits(
                        WaveCognitionLimits.MAX_WINDOWS + 1, 1, PredictionBudget.DEFAULT, HypothesisLimits.DEFAULT,
                        false)),
                () -> assertThrows(IllegalArgumentException.class, () -> new WaveCognitionLimits(1, 0,
                        PredictionBudget.DEFAULT, HypothesisLimits.DEFAULT, false)),
                () -> assertTrue(WaveCognitionLimits.DEFAULT.withVerifyReconstruction(true).verifyReconstruction()));
    }
}
