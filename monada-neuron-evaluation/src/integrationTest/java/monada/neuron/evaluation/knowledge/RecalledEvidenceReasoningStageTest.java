package monada.neuron.evaluation.knowledge;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.memory.ResonanceMemoryCognitiveStage;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.MemoryReferenceEvidence;
import monada.neuron.reasoning.ReasoningCognitiveStageResult;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The evaluation-only consumer attaches exactly the admitted references and passes every Signal through. */
class RecalledEvidenceReasoningStageTest {

    private static final Signal QUERY = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 7.0, 0.0));
    private static final ResonanceMemoryResult FIRST = result("rs-first", 11.0, 0.9);
    private static final ResonanceMemoryResult SECOND = result("rs-second", 12.0, 0.4);

    @Test
    void attachesOneSupportingReferencePerAdmittedResultInOrder() {
        var reasoning = reasoning(request -> new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE,
                request.maxResults(), List.of(FIRST, SECOND)), new CognitiveBudget(4, 64, 64));
        assertEquals(List.of(QUERY, FIRST.signal(), SECOND.signal()), reasoning.outputSignals());
        var hypotheses = reasoning.hypotheses().hypotheses();
        assertEquals(1, hypotheses.size());
        assertEquals(RecalledEvidenceReasoningStage.RECALLED_EVIDENCE, hypotheses.getFirst().proposition());
        assertEquals(List.of(new MemoryReferenceEvidence("rs-first", EvidenceRelation.SUPPORTS, 1.0),
                new MemoryReferenceEvidence("rs-second", EvidenceRelation.SUPPORTS, 1.0)),
                hypotheses.getFirst().evidence(), "the score is never read: every weight is the same constant");
    }

    @Test
    void attachesOnlyWhatTheMemoryResultHolds() {
        // A PARTIAL response holds only the first result; the consumer never sees, and so never attaches, the rest.
        var partial = reasoning(request -> new ResonanceMemoryResponse(ResonanceMemoryStatus.PARTIAL,
                request.maxResults(), List.of(FIRST)), new CognitiveBudget(4, 64, 64));
        assertEquals(List.of(new MemoryReferenceEvidence("rs-first", EvidenceRelation.SUPPORTS, 1.0)),
                partial.hypotheses().hypotheses().getFirst().evidence());

        // Budget 3 admits the memory input, its pass-through and the first result; truncating the second exhausts the
        // signal budget, so the cycle ends before the consumer runs: memory present, not used.
        var truncated = cycle(request -> new ResonanceMemoryResponse(ResonanceMemoryStatus.COMPLETE,
                request.maxResults(), List.of(FIRST, SECOND)))
                .execute(new PrimaryMonad(UUID.randomUUID()), List.of(QUERY), new CognitiveBudget(4, 3, 64));
        assertEquals(1, truncated.stageResults().size());
    }

    @Test
    void proposesNothingWithoutRecalledResults() {
        for (var status : List.of(ResonanceMemoryStatus.COMPLETE, ResonanceMemoryStatus.UNAVAILABLE,
                ResonanceMemoryStatus.FAILED)) {
            var reasoning = reasoning(request -> new ResonanceMemoryResponse(status, request.maxResults(), List.of()),
                    new CognitiveBudget(4, 64, 64));
            assertEquals(HypothesisSet.EMPTY, reasoning.hypotheses(), status.name());
            assertEquals(List.of(QUERY), reasoning.outputSignals(), status.name());
        }
        var alone = new DeterministicCognitiveCycle(List.of(new RecalledEvidenceReasoningStage(5)))
                .execute(new PrimaryMonad(UUID.randomUUID()), List.of(QUERY), new CognitiveBudget(4, 64, 64));
        assertTrue(((ReasoningCognitiveStageResult) alone.stageResults().getFirst()).hypotheses().isEmpty(),
                "without a memory result there is nothing to attach");
    }

    private static ReasoningCognitiveStageResult reasoning(ResonanceMemoryPort port, CognitiveBudget budget) {
        var result = cycle(port).execute(new PrimaryMonad(UUID.randomUUID()), List.of(QUERY), budget);
        return (ReasoningCognitiveStageResult) result.stageResults().get(1);
    }

    private static DeterministicCognitiveCycle cycle(ResonanceMemoryPort port) {
        return new DeterministicCognitiveCycle(List.of(new ResonanceMemoryCognitiveStage(port, 5),
                new RecalledEvidenceReasoningStage(5)));
    }

    private static ResonanceMemoryResult result(String reference, double frequency, double score) {
        return new ResonanceMemoryResult(reference, new Signal(SignalKind.INTERMEDIATE,
                new FrequencyState(0.5, frequency, 0.0)), score);
    }
}
