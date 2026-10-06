package monada.neuron.perception;

import monada.neuron.action.ObservationAdmission;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleException;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResultSnapshot;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerceptionCognitiveStageTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(10, 10, 20);

    private static HostExecutionContext context(String ref) {
        return HostExecutionContext.of(new HostReference(ref));
    }

    private static Signal observation(double amplitude) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static PrimaryMonad monad() {
        return new PrimaryMonad(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    private static PerceptionCognitiveStageResult stageResult(CognitiveCycleResult result) {
        return assertInstanceOf(PerceptionCognitiveStageResult.class, result.stageResults().getFirst());
    }

    /** Downstream stage that records whether it ran. */
    private static final class CountingStage implements CognitiveStage {
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public CognitiveStageKind kind() {
            return CognitiveStageKind.REASONING;
        }

        @Override
        public CognitiveStageResultSnapshot execute(
                PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
            executions.incrementAndGet();
            return new CognitiveStageResultSnapshot(kind(), CognitiveStageStatus.COMPLETED, inputSignals);
        }
    }

    @Test
    void emitsOrderedObservationsAndRetainsTheOutcomeWithItsHostContext() {
        var first = observation(1.0);
        var second = observation(2.0);
        var host = context("run-1");
        var capability = new DeterministicPerceptionCapability(Map.of(
                "run-1", new DeterministicPerceptionCapability.Script(
                        PerceptionStatus.SUCCEEDED, List.of(first, second))));

        var result = new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 2)))
                .execute(monad(), List.of(), BUDGET, Optional.of(host));
        var stage = stageResult(result);
        var request = new PerceptionRequest(2, Optional.of(host));

        assertAll(
                () -> assertEquals(List.of(first, second), result.outputSignals()),
                () -> assertEquals(
                        new PerceptionOutcome(request, new PerceptionResult(
                                PerceptionStatus.SUCCEEDED, 2, List.of(first, second))),
                        stage.outcome()),
                () -> assertEquals(List.of(request), capability.receivedRequests()),
                () -> assertEquals(CognitiveStageKind.PERCEPTION, stage.kind()),
                () -> assertEquals(CognitiveStageStatus.COMPLETED, stage.status()),
                () -> assertEquals(2, stage.producedSignalCount()),
                () -> assertEquals(2, stage.admittedSignalCount()),
                () -> assertEquals(ObservationAdmission.COMPLETE, stage.signalAdmission()),
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()));
    }

    @Test
    void stageIsASourceAtThePerceptionPosition() {
        var stage = new PerceptionCognitiveStage(request -> null, 1);

        assertAll(
                () -> assertEquals(CognitiveStageKind.PERCEPTION, stage.kind()),
                () -> assertTrue(stage.isSource()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStage(request -> null, 0)),
                () -> assertThrows(NullPointerException.class, () -> new PerceptionCognitiveStage(null, 1)));
    }

    @Test
    void nonSuccessOutcomesEndTheCycleWithNoSignalsAndKeepTheAdapterStatus() {
        for (var status : List.of(
                PerceptionStatus.EMPTY,
                PerceptionStatus.REJECTED,
                PerceptionStatus.UNAVAILABLE,
                PerceptionStatus.TIMED_OUT,
                PerceptionStatus.FAILED)) {
            var downstream = new CountingStage();
            PerceptionCapability capability = request ->
                    new PerceptionResult(status, request.maxSignals(), List.of());

            var result = new DeterministicCognitiveCycle(List.of(
                    new PerceptionCognitiveStage(capability, 2), downstream))
                    .execute(monad(), List.of(), BUDGET);

            assertAll(
                    () -> assertEquals(status, stageResult(result).outcome().result().status()),
                    () -> assertEquals(CognitiveCycleTermination.NO_SIGNALS, result.termination()),
                    () -> assertEquals(0, downstream.executions.get()),
                    () -> assertEquals(List.of(), result.outputSignals()));
        }
    }

    @Test
    void adapterPartialCompletionIsKeptAndIsNotTruncation() {
        var first = observation(1.0);
        var second = observation(2.0);
        PerceptionCapability capability = request -> new PerceptionResult(
                PerceptionStatus.PARTIALLY_COMPLETED, request.maxSignals(), List.of(first, second));

        var result = new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 3)))
                .execute(monad(), List.of(), BUDGET);
        var stage = stageResult(result);

        assertAll(
                () -> assertEquals(PerceptionStatus.PARTIALLY_COMPLETED, stage.outcome().result().status()),
                () -> assertEquals(ObservationAdmission.COMPLETE, stage.signalAdmission()),
                () -> assertEquals(List.of(first, second), result.outputSignals()));
    }

    @Test
    void cycleBudgetTruncationKeepsTheReportedSuccessAndRecordsTruncation() {
        var first = observation(1.0);
        var second = observation(2.0);
        var rejected = observation(3.0);
        PerceptionCapability capability = request -> new PerceptionResult(
                PerceptionStatus.SUCCEEDED, request.maxSignals(), List.of(first, second, rejected));

        var result = new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 3)))
                .execute(monad(), List.of(), new CognitiveBudget(10, 2, 20));
        var stage = stageResult(result);

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED, result.termination()),
                () -> assertEquals(List.of(first, second), result.outputSignals()),
                // the budget limits what the cycle keeps; it never rewrites SUCCEEDED to PARTIALLY_COMPLETED
                () -> assertEquals(PerceptionStatus.SUCCEEDED, stage.outcome().result().status()),
                () -> assertEquals(3, stage.producedSignalCount()),
                () -> assertEquals(2, stage.admittedSignalCount()),
                () -> assertEquals(ObservationAdmission.TRUNCATED, stage.signalAdmission()));
    }

    @Test
    void adapterPartialCompletionAndBudgetTruncationAreIndependent() {
        var first = observation(1.0);
        var second = observation(2.0);
        PerceptionCapability capability = request -> new PerceptionResult(
                PerceptionStatus.PARTIALLY_COMPLETED, request.maxSignals(), List.of(first, second));

        var result = new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 2)))
                .execute(monad(), List.of(), new CognitiveBudget(10, 1, 20));
        var stage = stageResult(result);

        assertAll(
                () -> assertEquals(PerceptionStatus.PARTIALLY_COMPLETED, stage.outcome().result().status()),
                () -> assertEquals(ObservationAdmission.TRUNCATED, stage.signalAdmission()),
                () -> assertEquals(List.of(first), result.outputSignals()));
    }

    @Test
    void resultRecordRejectsCountersThatDisagreeWithAdmission() {
        var first = observation(1.0);
        var second = observation(2.0);
        var outcome = new PerceptionOutcome(
                new PerceptionRequest(2),
                new PerceptionResult(PerceptionStatus.SUCCEEDED, 2, List.of(first, second)));
        var complete = ObservationAdmission.COMPLETE;
        var truncated = ObservationAdmission.TRUNCATED;

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStageResult(outcome, 1, List.of(first), truncated)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStageResult(outcome, 2, List.of(first), complete)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStageResult(outcome, 2, List.of(first, second), truncated)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStageResult(outcome, 2, List.of(second), truncated)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PerceptionCognitiveStageResult(
                                outcome, 2, List.of(first, second, observation(3.0)), complete)),
                () -> assertEquals(2, new PerceptionCognitiveStageResult(outcome).producedSignalCount()));
    }

    @Test
    void fullBudgetTruncationKeepsTheSuccessfulOutcomeAndAdmitsNoSignal() {
        var produced = observation(1.0);
        var outcome = new PerceptionOutcome(
                new PerceptionRequest(1),
                new PerceptionResult(PerceptionStatus.SUCCEEDED, 1, List.of(produced)));

        var admitted = new PerceptionCognitiveStageResult(outcome).withAdmittedOutputSignals(List.of());

        assertAll(
                () -> assertEquals(PerceptionStatus.SUCCEEDED, admitted.outcome().result().status()),
                () -> assertEquals(List.of(produced), admitted.outcome().result().signals()),
                () -> assertEquals(1, admitted.producedSignalCount()),
                () -> assertEquals(0, admitted.admittedSignalCount()),
                () -> assertEquals(List.of(), admitted.outputSignals()),
                () -> assertEquals(ObservationAdmission.TRUNCATED, admitted.signalAdmission()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> admitted.withAdmittedOutputSignals(List.of(observation(9.0)))));
    }

    @Test
    void theStageRejectsInputSignalsWhenExecutedDirectly() {
        var stage = new PerceptionCognitiveStage(
                request -> new PerceptionResult(PerceptionStatus.EMPTY, request.maxSignals(), List.of()), 1);

        assertThrows(IllegalArgumentException.class, () -> stage.execute(
                monad(), List.of(observation(1.0)), new CognitiveContext(BUDGET, Optional.empty())));
    }

    @Test
    void anInvalidAdapterResultIsAnOperationalFailureNotATrimmedBatch() {
        var first = observation(1.0);
        var second = observation(2.0);
        PerceptionCapability overLimit = request ->
                new PerceptionResult(PerceptionStatus.SUCCEEDED, 1, List.of(first, second));
        PerceptionCapability wrongLimit = request ->
                new PerceptionResult(PerceptionStatus.EMPTY, request.maxSignals() + 1, List.of());
        PerceptionCapability nullResult = request -> null;

        for (var capability : List.of(overLimit, wrongLimit, nullResult)) {
            var failure = assertThrows(CognitiveCycleException.class,
                    () -> new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 1)))
                            .execute(monad(), List.of(), BUDGET));
            assertEquals(CognitiveStageKind.PERCEPTION, failure.failedStage());
        }
    }

    @Test
    void anUnexpectedAdapterExceptionPropagatesAsACycleFailureOfThePerceptionStage() {
        var cause = new IllegalStateException("adapter exploded");
        PerceptionCapability capability = request -> {
            throw cause;
        };

        var failure = assertThrows(CognitiveCycleException.class,
                () -> new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 1)))
                        .execute(monad(), List.of(), BUDGET));

        assertAll(
                () -> assertEquals(CognitiveStageKind.PERCEPTION, failure.failedStage()),
                () -> assertEquals(cause, failure.getCause()));
    }

    @Test
    void missingContextReachesTheAdapterAsEmptyAndIsAnExpectedRejection() {
        var capability = new DeterministicPerceptionCapability(Map.of());

        var result = new DeterministicCognitiveCycle(List.of(new PerceptionCognitiveStage(capability, 1)))
                .execute(monad(), List.of(), BUDGET);

        assertAll(
                () -> assertEquals(List.of(new PerceptionRequest(1)), capability.receivedRequests()),
                () -> assertEquals(PerceptionStatus.REJECTED, stageResult(result).outcome().result().status()),
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()));
    }
}
