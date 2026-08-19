package monada.neuron.memory;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.context.CognitiveSignalOccurrence;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleException;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResonanceMemoryCognitiveStageTest {

    @Test
    void recallsOneOrderedBatchBeforeReasoningAndPreservesDuplicateTies() {
        var first = signal(1.0);
        var second = signal(2.0);
        var firstMatch = result("first", 3.0, 0.8);
        var secondMatch = result("second", 4.0, 0.8);
        var duplicateMatch = result("second", 4.0, 0.8);
        var request = new ResonanceMemoryRequest(List.of(first, second), 3);
        var response = new ResonanceMemoryResponse(
                ResonanceMemoryStatus.COMPLETE,
                3,
                List.of(firstMatch, secondMatch, duplicateMatch));
        var adapter = new DeterministicResonanceMemoryAdapter(java.util.Map.of(request, response));
        var receivedByReasoning = new AtomicReference<List<Signal>>();
        var expectedSignals = List.of(first, second, firstMatch.signal(), secondMatch.signal(), duplicateMatch.signal());
        var cycle = new DeterministicCognitiveCycle(List.of(
                passThroughReasoning(receivedByReasoning),
                new ResonanceMemoryCognitiveStage(adapter, 3)));

        var cycleResult = cycle.execute(
                monad(),
                List.of(first, second),
                new CognitiveBudget(10, 30, 30));
        var memoryResult = assertInstanceOf(
                ResonanceMemoryStageResult.class,
                cycleResult.stageResults().getFirst());

        assertAll(
                () -> assertEquals(expectedSignals, receivedByReasoning.get()),
                () -> assertEquals(expectedSignals, cycleResult.outputSignals()),
                () -> assertEquals(response, memoryResult.response()),
                () -> assertEquals(List.of(request), adapter.receivedRequests()),
                () -> assertEquals(List.of(CognitiveStageKind.MEMORY_RECALL, CognitiveStageKind.REASONING),
                        cycleResult.stageResults().stream().map(CognitiveStageResult::kind).toList()));
    }

    @Test
    void expectedMemoryOutcomesPassInputsThroughToReasoning() {
        for (var status : List.of(
                ResonanceMemoryStatus.COMPLETE,
                ResonanceMemoryStatus.UNAVAILABLE,
                ResonanceMemoryStatus.TIMED_OUT,
                ResonanceMemoryStatus.FAILED)) {
            var input = signal(1.0);
            var receivedByReasoning = new AtomicReference<List<Signal>>();
            ResonanceMemoryPort port = request -> new ResonanceMemoryResponse(
                    status,
                    request.maxResults(),
                    List.of());
            var result = new DeterministicCognitiveCycle(List.of(
                    new ResonanceMemoryCognitiveStage(port, 2),
                    passThroughReasoning(receivedByReasoning))).execute(
                    monad(),
                    List.of(input),
                    new CognitiveBudget(10, 20, 20));
            var memoryResult = assertInstanceOf(ResonanceMemoryStageResult.class,
                    result.stageResults().getFirst());

            assertAll(
                    () -> assertEquals(List.of(input), receivedByReasoning.get()),
                    () -> assertEquals(List.of(input), result.outputSignals()),
                    () -> assertEquals(status, memoryResult.response().status()),
                    () -> assertEquals(CognitiveStageStatus.COMPLETED, memoryResult.status()));
        }
    }

    @Test
    void partialRecallContinuesWithItsAvailablePrefix() {
        var input = signal(1.0);
        var match = result("partial", 2.0, 0.4);
        ResonanceMemoryPort port = request -> new ResonanceMemoryResponse(
                ResonanceMemoryStatus.PARTIAL,
                request.maxResults(),
                List.of(match));
        var result = new DeterministicCognitiveCycle(List.of(
                new ResonanceMemoryCognitiveStage(port, 2))).execute(
                monad(),
                List.of(input),
                new CognitiveBudget(10, 10, 20));
        var memoryResult = assertInstanceOf(ResonanceMemoryStageResult.class,
                result.stageResults().getFirst());

        assertAll(
                () -> assertEquals(List.of(input, match.signal()), result.outputSignals()),
                () -> assertEquals(ResonanceMemoryStatus.PARTIAL, memoryResult.response().status()),
                () -> assertEquals(List.of(match), memoryResult.response().results()));
    }

    @Test
    void cycleBudgetAdmitsOnlyTheMemoryOutputPrefixAndMarksCompleteResponsePartial() {
        var input = signal(1.0);
        var firstMatch = result("first", 2.0, 0.5);
        var secondMatch = result("second", 3.0, 0.4);
        ResonanceMemoryPort port = request -> new ResonanceMemoryResponse(
                ResonanceMemoryStatus.COMPLETE,
                request.maxResults(),
                List.of(firstMatch, secondMatch));

        var result = new DeterministicCognitiveCycle(List.of(
                new ResonanceMemoryCognitiveStage(port, 2))).execute(
                monad(),
                List.of(input),
                new CognitiveBudget(10, 3, 20));
        var memoryResult = assertInstanceOf(ResonanceMemoryStageResult.class,
                result.stageResults().getFirst());

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED,
                        result.termination()),
                () -> assertEquals(List.of(input, firstMatch.signal()), result.outputSignals()),
                () -> assertEquals(ResonanceMemoryStatus.PARTIAL, memoryResult.response().status()),
                () -> assertEquals(List.of(firstMatch), memoryResult.response().results()),
                () -> assertEquals(3, result.snapshot().acceptedSignals()),
                () -> assertEquals(List.of(
                        CognitiveSignalOccurrence.StageInput.class,
                        CognitiveSignalOccurrence.StageOutput.class,
                        CognitiveSignalOccurrence.StageOutput.class), result.snapshot().signalOccurrences().stream()
                        .map(Object::getClass)
                        .toList()));
    }

    @Test
    void cycleAdmitsOnlyTheInputPrefixBeforeCallingTheMemoryPort() {
        var first = signal(1.0);
        var second = signal(2.0);
        var third = signal(3.0);
        var receivedRequest = new AtomicReference<ResonanceMemoryRequest>();
        ResonanceMemoryPort port = request -> {
            receivedRequest.set(request);
            return new ResonanceMemoryResponse(
                    ResonanceMemoryStatus.COMPLETE,
                    request.maxResults(),
                    List.of());
        };

        var result = new DeterministicCognitiveCycle(List.of(
                new ResonanceMemoryCognitiveStage(port, 1))).execute(
                monad(),
                List.of(first, second, third),
                new CognitiveBudget(10, 2, 20));

        assertAll(
                () -> assertEquals(new ResonanceMemoryRequest(List.of(first, second), 1),
                        receivedRequest.get()),
                () -> assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED,
                        result.termination()),
                () -> assertEquals(List.of(), result.outputSignals()),
                () -> assertEquals(2, result.snapshot().acceptedSignals()));
    }

    @Test
    void memoryRecallIsOptionalAndUnexpectedPortFailuresPreserveCycleFailureSemantics() {
        var input = signal(1.0);
        var optionalResult = new DeterministicCognitiveCycle(List.of(
                passThroughReasoning(new AtomicReference<>()))).execute(
                monad(),
                List.of(input),
                new CognitiveBudget(10, 10, 20));
        var originalFailure = new IllegalStateException("memory transport failed");
        var failure = assertThrows(CognitiveCycleException.class,
                () -> new DeterministicCognitiveCycle(List.of(
                        new ResonanceMemoryCognitiveStage(request -> {
                            throw originalFailure;
                        }, 1))).execute(
                        monad(),
                        List.of(input),
                        new CognitiveBudget(10, 10, 20)));

        assertAll(
                () -> assertEquals(List.of(input), optionalResult.outputSignals()),
                () -> assertEquals(CognitiveCycleOutcome.SUCCESS, optionalResult.snapshot().outcome()),
                () -> assertSame(originalFailure, failure.getCause()),
                () -> assertEquals(CognitiveStageKind.MEMORY_RECALL, failure.failedStage()),
                () -> assertEquals(CognitiveCycleOutcome.FAILURE, failure.snapshot().outcome()));
    }

    @Test
    void responseLimitMismatchFailsMemoryRecallBeforeAResultIsRetained() {
        var laterStageCalls = new AtomicInteger();
        var failure = assertThrows(CognitiveCycleException.class,
                () -> new DeterministicCognitiveCycle(List.of(
                        new ResonanceMemoryCognitiveStage(request -> new ResonanceMemoryResponse(
                                ResonanceMemoryStatus.COMPLETE,
                                request.maxResults() + 1,
                                List.of()), 1),
                        passThroughReasoning(new AtomicReference<>(), laterStageCalls))).execute(
                        monad(),
                        List.of(signal(1.0)),
                        new CognitiveBudget(10, 10, 20)));

        assertAll(
                () -> assertEquals(CognitiveStageKind.MEMORY_RECALL, failure.failedStage()),
                () -> assertEquals(0, failure.completedStageResults().size()),
                () -> assertEquals(0, laterStageCalls.get()),
                () -> assertFalse(failure.snapshot().traceEntries().isEmpty()));
    }

    private CognitiveStage passThroughReasoning(AtomicReference<List<Signal>> receivedSignals) {
        return passThroughReasoning(receivedSignals, new AtomicInteger());
    }

    private CognitiveStage passThroughReasoning(
            AtomicReference<List<Signal>> receivedSignals,
            AtomicInteger calls) {
        return new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.REASONING;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad,
                    List<Signal> inputSignals,
                    CognitiveContext context) {
                calls.incrementAndGet();
                receivedSignals.set(List.copyOf(inputSignals));
                return new TestStageResult(kind(), CognitiveStageStatus.COMPLETED, inputSignals);
            }
        };
    }

    private PrimaryMonad monad() {
        return new PrimaryMonad(new UUID(0L, 1L));
    }

    private ResonanceMemoryResult result(String reference, double amplitude, double score) {
        return new ResonanceMemoryResult(reference, signal(amplitude), score);
    }

    private Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private record TestStageResult(
            CognitiveStageKind kind,
            CognitiveStageStatus status,
            List<Signal> outputSignals) implements CognitiveStageResult {

        private TestStageResult {
            outputSignals = List.copyOf(outputSignals);
        }
    }
}
