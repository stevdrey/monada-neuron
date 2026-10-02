package monada.neuron.reasoning;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResultSnapshot;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningCognitiveStageResultTest {

    private final Signal first = signal(1.0);
    private final Signal second = signal(2.0);
    private final HypothesisSet hypotheses = hypothesisSet();

    @Test
    void reportsReasoningKindAndSnapshotsSignals() {
        var result = new ReasoningCognitiveStageResult(
                CognitiveStageStatus.COMPLETED, List.of(first), hypotheses);
        assertEquals(CognitiveStageKind.REASONING, result.kind());
    }

    @Test
    void admissionTrimsSignalsButKeepsHypotheses() {
        var result = new ReasoningCognitiveStageResult(
                CognitiveStageStatus.COMPLETED, List.of(first, second), hypotheses);
        var admitted = assertInstanceOf(
                ReasoningCognitiveStageResult.class,
                result.withAdmittedOutputSignals(List.of(first)));
        assertAll(
                () -> assertEquals(List.of(first), admitted.outputSignals()),
                () -> assertSame(hypotheses, admitted.hypotheses()));
    }

    @Test
    void admissionRejectsNonPrefix() {
        var result = new ReasoningCognitiveStageResult(
                CognitiveStageStatus.COMPLETED, List.of(first, second), hypotheses);
        assertThrows(IllegalArgumentException.class,
                () -> result.withAdmittedOutputSignals(List.of(second)));
    }

    @Test
    void hypothesesOfReturnsEmptyUnlessPreviousIsReasoning() {
        var reasoning = new ReasoningCognitiveStageResult(
                CognitiveStageStatus.COMPLETED, List.of(), hypotheses);
        var other = new CognitiveStageResultSnapshot(
                CognitiveStageKind.PERCEPTION, CognitiveStageStatus.COMPLETED, List.of());
        assertAll(
                () -> assertSame(hypotheses, ReasoningCognitiveStageResult.hypothesesOf(Optional.of(reasoning))),
                () -> assertSame(HypothesisSet.EMPTY, ReasoningCognitiveStageResult.hypothesesOf(Optional.of(other))),
                () -> assertSame(HypothesisSet.EMPTY, ReasoningCognitiveStageResult.hypothesesOf(Optional.empty())));
    }

    @Test
    void provenanceRejectsSignalSequencesTheContextNeverAccepted() {
        try (var context = new CognitiveContext(new CognitiveBudget(10, 10, 10))) {
            context.tryRecordCognitiveStageInputSignal(CognitiveStageKind.REASONING, first);
            assertAll(
                    () -> withSignalEvidence(0).validateProvenance(context),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> withSignalEvidence(1).validateProvenance(context)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> withSignalEvidence(Long.MAX_VALUE).validateProvenance(context)));
        }
    }

    @Test
    void retainsTypedHandOffOnlyWithHypotheses() {
        assertAll(
                () -> assertTrue(new ReasoningCognitiveStageResult(
                        CognitiveStageStatus.COMPLETED, List.of(), hypotheses).retainsTypedHandOff()),
                () -> assertFalse(new ReasoningCognitiveStageResult(
                        CognitiveStageStatus.COMPLETED, List.of(), HypothesisSet.EMPTY).retainsTypedHandOff()));
    }

    private static ReasoningCognitiveStageResult withSignalEvidence(long sequence) {
        var builder = new HypothesisSetBuilder(HypothesisLimits.DEFAULT);
        var seq = builder.propose(new Proposition(0, 1)).getAsInt();
        builder.addEvidence(seq, new SignalEvidence(sequence, EvidenceRelation.SUPPORTS, 0.5));
        return new ReasoningCognitiveStageResult(
                CognitiveStageStatus.COMPLETED, List.of(), builder.build());
    }

    private static HypothesisSet hypothesisSet() {
        var builder = new HypothesisSetBuilder(HypothesisLimits.DEFAULT);
        builder.propose(new Proposition(0, 1));
        return builder.build();
    }

    private static Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }
}
