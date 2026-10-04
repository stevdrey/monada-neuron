package monada.neuron.action;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.context.CognitiveSignalOccurrence;
import monada.neuron.context.CognitiveContext;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionCognitiveStageTest {

    @Test
    void executesOneOrderedActionRequestAndExposesOnlyItsObservations() {
        var firstInput = signal(1.0);
        var secondInput = signal(2.0);
        var firstObservation = signal(3.0);
        var duplicateObservation = signal(3.0);
        var request = new ActionRequest(List.of(firstInput, secondInput), 2);
        var actionResult = new ActionResult(
                ActionStatus.SUCCEEDED,
                2,
                List.of(firstObservation, duplicateObservation));
        var executor = new DeterministicActionExecutor(Map.of(request, actionResult));

        var cycleResult = new DeterministicCognitiveCycle(List.of(
                new ActionCognitiveStage(executor, 2))).execute(
                monad(),
                List.of(firstInput, secondInput),
                new CognitiveBudget(10, 10, 20));
        var stageResult = assertInstanceOf(
                ActionCognitiveStageResult.class,
                cycleResult.stageResults().getFirst());

        assertAll(
                () -> assertEquals(List.of(firstObservation, duplicateObservation), cycleResult.outputSignals()),
                () -> assertEquals(new ActionOutcome(request, actionResult), stageResult.outcome()),
                () -> assertEquals(List.of(request), executor.receivedRequests()),
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, cycleResult.termination()),
                () -> assertEquals(CognitiveStageStatus.COMPLETED, stageResult.status()));
    }

    @Test
    void expectedActionOutcomesCompleteTheCycleWithoutOutputSignals() {
        for (var actionStatus : List.of(
                ActionStatus.REJECTED,
                ActionStatus.UNAVAILABLE,
                ActionStatus.TIMED_OUT,
                ActionStatus.FAILED)) {
            ActionCapability capability = request -> new ActionResult(
                    actionStatus,
                    request.maxObservations(),
                    List.of());

            var result = new DeterministicCognitiveCycle(List.of(
                    new ActionCognitiveStage(capability, 2))).execute(
                    monad(),
                    List.of(signal(1.0)),
                    new CognitiveBudget(10, 10, 20));
            var stageResult = assertInstanceOf(ActionCognitiveStageResult.class,
                    result.stageResults().getFirst());

            assertAll(
                    () -> assertEquals(actionStatus, stageResult.outcome().result().status()),
                    () -> assertEquals(List.of(), result.outputSignals()),
                    () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                    () -> assertEquals(CognitiveCycleOutcome.SUCCESS, result.snapshot().outcome()));
        }
    }

    @Test
    void preservesCapabilityReportedPartialCompletionAndObservationOrder() {
        var input = signal(1.0);
        var firstObservation = signal(2.0);
        var secondObservation = signal(3.0);
        ActionCapability capability = request -> new ActionResult(
                ActionStatus.PARTIALLY_COMPLETED,
                request.maxObservations(),
                List.of(firstObservation, secondObservation));

        var result = new DeterministicCognitiveCycle(List.of(
                new ActionCognitiveStage(capability, 3))).execute(
                monad(),
                List.of(input),
                new CognitiveBudget(10, 10, 20));
        var stageResult = assertInstanceOf(ActionCognitiveStageResult.class,
                result.stageResults().getFirst());

        assertAll(
                () -> assertEquals(List.of(firstObservation, secondObservation), result.outputSignals()),
                () -> assertEquals(ActionStatus.PARTIALLY_COMPLETED, stageResult.outcome().result().status()));
    }

    @Test
    void cycleBudgetAdmitsOnlyTheActionObservationPrefixAndKeepsTheReportedSuccess() {
        var input = signal(1.0);
        var firstObservation = signal(2.0);
        var secondObservation = signal(3.0);
        var rejectedObservation = signal(4.0);
        ActionCapability capability = request -> new ActionResult(
                ActionStatus.SUCCEEDED,
                request.maxObservations(),
                List.of(firstObservation, secondObservation, rejectedObservation));

        var result = new DeterministicCognitiveCycle(List.of(
                new ActionCognitiveStage(capability, 3))).execute(
                monad(),
                List.of(input),
                new CognitiveBudget(10, 3, 20));
        var stageResult = assertInstanceOf(ActionCognitiveStageResult.class,
                result.stageResults().getFirst());

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED, result.termination()),
                () -> assertEquals(List.of(firstObservation, secondObservation), result.outputSignals()),
                // the budget limits what the cycle keeps; it does not turn a success into a partial one
                () -> assertEquals(ActionStatus.SUCCEEDED, stageResult.outcome().result().status()),
                () -> assertEquals(3, stageResult.producedObservationCount()),
                () -> assertEquals(2, stageResult.admittedObservationCount()),
                () -> assertEquals(ObservationAdmission.TRUNCATED, stageResult.observationAdmission()),
                () -> assertEquals(List.of(firstObservation, secondObservation),
                        stageResult.outcome().result().observations()),
                () -> assertEquals(3, result.snapshot().acceptedSignals()),
                () -> assertFalse(result.snapshot().signalOccurrences().stream()
                        .map(CognitiveSignalOccurrence::signal)
                        .anyMatch(rejectedObservation::equals)));
    }

    @Test
    void cycleBudgetNeverRedefinesTheStatusOfEitherReportedCompletion() {
        var observations = List.of(signal(2.0), signal(3.0), signal(4.0));
        for (var reported : List.of(ActionStatus.SUCCEEDED, ActionStatus.PARTIALLY_COMPLETED)) {
            ActionCapability capability = request -> new ActionResult(reported, request.maxObservations(), observations);

            // maxSignals 3 admits the input and two of the three observations; 4 admits all of them
            var truncated = new DeterministicCognitiveCycle(List.of(new ActionCognitiveStage(capability, 3)))
                    .execute(monad(), List.of(signal(1.0)), new CognitiveBudget(10, 3, 20));
            var complete = new DeterministicCognitiveCycle(List.of(new ActionCognitiveStage(capability, 3)))
                    .execute(monad(), List.of(signal(1.0)), new CognitiveBudget(10, 4, 20));
            var truncatedStage = assertInstanceOf(ActionCognitiveStageResult.class, truncated.stageResults().getFirst());
            var completeStage = assertInstanceOf(ActionCognitiveStageResult.class, complete.stageResults().getFirst());

            assertAll(
                    reported.name(),
                    () -> assertEquals(reported, truncatedStage.outcome().result().status()),
                    () -> assertEquals(ObservationAdmission.TRUNCATED, truncatedStage.observationAdmission()),
                    () -> assertEquals(3, truncatedStage.producedObservationCount()),
                    () -> assertEquals(2, truncatedStage.admittedObservationCount()),
                    () -> assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED, truncated.termination()),
                    () -> assertEquals(reported, completeStage.outcome().result().status()),
                    () -> assertEquals(ObservationAdmission.COMPLETE, completeStage.observationAdmission()),
                    () -> assertEquals(3, completeStage.admittedObservationCount()));
        }
    }

    @Test
    void cycleAdmitsOnlyTheInputPrefixBeforeCallingTheActionCapability() {
        var first = signal(1.0);
        var second = signal(2.0);
        var third = signal(3.0);
        var receivedRequest = new AtomicReference<ActionRequest>();
        ActionCapability capability = request -> {
            receivedRequest.set(request);
            return new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(), List.of());
        };

        var result = new DeterministicCognitiveCycle(List.of(
                new ActionCognitiveStage(capability, 1))).execute(
                monad(),
                List.of(first, second, third),
                new CognitiveBudget(10, 2, 20));

        assertAll(
                () -> assertEquals(new ActionRequest(List.of(first, second), 1), receivedRequest.get()),
                () -> assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED, result.termination()),
                () -> assertEquals(List.of(), result.outputSignals()),
                () -> assertEquals(2, result.snapshot().acceptedSignals()));
    }

    @Test
    void unexpectedCapabilityFailuresAndInvalidResultsPreserveCycleFailureSemantics() {
        var input = signal(1.0);
        var originalFailure = new IllegalStateException("action transport failed");
        var thrown = assertThrows(CognitiveCycleException.class,
                () -> new DeterministicCognitiveCycle(List.of(
                        new ActionCognitiveStage(request -> {
                            throw originalFailure;
                        }, 1))).execute(
                        monad(),
                        List.of(input),
                        new CognitiveBudget(10, 10, 20)));
        var mismatchedLimit = assertThrows(CognitiveCycleException.class,
                () -> new DeterministicCognitiveCycle(List.of(
                        new ActionCognitiveStage(request -> new ActionResult(
                                ActionStatus.SUCCEEDED,
                                request.maxObservations() + 1,
                                List.of()), 1))).execute(
                        monad(),
                        List.of(input),
                        new CognitiveBudget(10, 10, 20)));
        var nullResult = assertThrows(CognitiveCycleException.class,
                () -> new DeterministicCognitiveCycle(List.of(
                        new ActionCognitiveStage(request -> null, 1))).execute(
                        monad(),
                        List.of(input),
                        new CognitiveBudget(10, 10, 20)));

        assertAll(
                () -> assertSame(originalFailure, thrown.getCause()),
                () -> assertEquals(CognitiveStageKind.ACTION, thrown.failedStage()),
                () -> assertEquals(CognitiveCycleOutcome.FAILURE, thrown.snapshot().outcome()),
                () -> assertEquals(CognitiveStageKind.ACTION, mismatchedLimit.failedStage()),
                () -> assertEquals(0, mismatchedLimit.completedStageResults().size()),
                () -> assertEquals(CognitiveStageKind.ACTION, nullResult.failedStage()),
                () -> assertInstanceOf(NullPointerException.class, nullResult.getCause()));
    }

    @Test
    void actionStageSharesTheSingleActionSlotAndAPlanWithoutItPassesSignalsThrough() {
        var actionStage = new ActionCognitiveStage(
                request -> new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(), List.of()), 1);
        var input = signal(1.0);
        var passThrough = new DeterministicCognitiveCycle(List.of()).execute(
                monad(),
                List.of(input),
                new CognitiveBudget(10, 10, 20));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DeterministicCognitiveCycle(List.of(actionStage, actionSlot()))),
                () -> assertEquals(List.of(input), passThrough.outputSignals()),
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, passThrough.termination()));
    }

    private CognitiveStage actionSlot() {
        return new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.ACTION;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad,
                    List<Signal> inputSignals,
                    CognitiveContext context) {
                return new CognitiveStageResult() {
                    @Override
                    public CognitiveStageKind kind() {
                        return CognitiveStageKind.ACTION;
                    }

                    @Override
                    public CognitiveStageStatus status() {
                        return CognitiveStageStatus.COMPLETED;
                    }

                    @Override
                    public List<Signal> outputSignals() {
                        return List.copyOf(inputSignals);
                    }
                };
            }
        };
    }

    private PrimaryMonad monad() {
        return new PrimaryMonad(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    private Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }
}
