package monada.neuron.evaluation;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisSetBuilder;
import monada.neuron.reasoning.Proposition;
import monada.neuron.reasoning.SignalEvidence;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationCognitiveStageResultTest {

    private final HypothesisSet set = hypotheses();
    private final HypothesisEvaluation evaluation = new ReferenceHypothesisEvaluationPolicy().evaluate(set, 1);

    @Test
    void reportsEvaluationKindAndCarriesTypedData() {
        var result = result(List.of(signal(1.0)));
        assertAll(
                () -> assertEquals(CognitiveStageKind.EVALUATION, result.kind()),
                () -> assertEquals(CognitiveStageStatus.COMPLETED, result.status()),
                () -> assertEquals(set, result.evaluated()),
                () -> assertEquals(evaluation, result.evaluation()),
                () -> assertFalse(result.retainsTypedHandOff()));
    }

    @Test
    void trimsSignalsToAdmittedPrefixAndKeepsTypedData() {
        var first = signal(1.0);
        var second = signal(2.0);
        var trimmed = result(List.of(first, second)).withAdmittedOutputSignals(List.of(first));
        var typed = (EvaluationCognitiveStageResult) trimmed;
        assertAll(
                () -> assertEquals(List.of(first), typed.outputSignals()),
                () -> assertEquals(set, typed.evaluated()),
                () -> assertEquals(evaluation, typed.evaluation()));
    }

    @Test
    void rejectsAdmittedSignalsThatAreNotAPrefix() {
        var result = result(List.of(signal(1.0), signal(2.0)));
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> result.withAdmittedOutputSignals(List.of(signal(2.0)))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> result.withAdmittedOutputSignals(List.of(signal(1.0), signal(2.0), signal(3.0)))));
    }

    @Test
    void rejectsEvaluationInconsistentWithEvaluatedSet() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new EvaluationCognitiveStageResult(
                        CognitiveStageStatus.COMPLETED, List.of(), HypothesisSet.EMPTY, evaluation)),
                () -> assertThrows(NullPointerException.class, () -> new EvaluationCognitiveStageResult(
                        null, List.of(), set, evaluation)),
                () -> assertThrows(NullPointerException.class, () -> new EvaluationCognitiveStageResult(
                        CognitiveStageStatus.COMPLETED, null, set, evaluation)),
                () -> assertThrows(NullPointerException.class, () -> new EvaluationCognitiveStageResult(
                        CognitiveStageStatus.COMPLETED, List.of(), null, evaluation)),
                () -> assertThrows(NullPointerException.class, () -> new EvaluationCognitiveStageResult(
                        CognitiveStageStatus.COMPLETED, List.of(), set, null)));
    }

    @Test
    void snapshotsOutputSignals() {
        var mutable = new ArrayList<>(List.of(signal(1.0)));
        var result = result(mutable);
        mutable.clear();
        assertEquals(1, result.outputSignals().size());
    }

    @Test
    void provenanceValidationIsAcceptedForAnyContext() throws Exception {
        try (var context = new CognitiveContext(new CognitiveBudget(10, 10, 10))) {
            assertDoesNotThrow(() -> result(List.of()).validateProvenance(context));
        }
    }

    private EvaluationCognitiveStageResult result(List<Signal> outputs) {
        return new EvaluationCognitiveStageResult(CognitiveStageStatus.COMPLETED, outputs, set, evaluation);
    }

    private static HypothesisSet hypotheses() {
        var builder = new HypothesisSetBuilder(new HypothesisLimits(2, 2));
        var zero = builder.propose(new Proposition(0, 0)).getAsInt();
        var one = builder.propose(new Proposition(0, 1)).getAsInt();
        builder.addEvidence(zero, new SignalEvidence(0, EvidenceRelation.SUPPORTS, 0.3));
        builder.addEvidence(one, new SignalEvidence(0, EvidenceRelation.SUPPORTS, 0.9));
        return builder.build();
    }

    private static Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }
}
