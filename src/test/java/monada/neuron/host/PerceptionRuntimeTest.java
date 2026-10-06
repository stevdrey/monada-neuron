package monada.neuron.host;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.model.FrequencyState;
import monada.neuron.monad.CognitiveCycleException;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.perception.DeterministicPerceptionCapability;
import monada.neuron.perception.DeterministicPerceptionCapability.Script;
import monada.neuron.perception.PerceptionCapability;
import monada.neuron.perception.PerceptionCognitiveStageResult;
import monada.neuron.perception.PerceptionRequest;
import monada.neuron.perception.PerceptionResult;
import monada.neuron.perception.PerceptionStatus;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerceptionRuntimeTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(100, 100, 100);

    private static HostExecutionContext host(String ref) {
        return HostExecutionContext.of(new HostReference(ref));
    }

    private static Signal observation(double amplitude) {
        return new Signal(SignalKind.OBSERVATION, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private static NeuronRuntime.Builder runtimeBuilder() {
        return NeuronRuntime.builder()
                .monad(new PrimaryMonad(new UUID(0L, 1L)))
                .defaultBudget(BUDGET);
    }

    @Test
    void perceivedObservationsFlowThroughTheCanonicalCycleToTheActionStage() {
        var first = observation(1.0);
        var second = observation(2.0);
        var perception = new DeterministicPerceptionCapability(Map.of(
                "run-1", new Script(PerceptionStatus.SUCCEEDED, List.of(first, second))));
        var actionInputs = new ArrayList<List<Signal>>();
        ActionCapability action = request -> {
            actionInputs.add(request.inputSignals());
            return new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(), List.of());
        };
        var runtime = runtimeBuilder()
                .perceptionCapability(perception, 4)
                .actionCapability(action, 1)
                .build();
        var context = host("run-1");

        var result = runtime.execute(CycleInput.of(List.of()).withHostContext(context));

        assertAll(
                () -> assertEquals(List.of(List.of(first, second)), actionInputs),
                () -> assertEquals(
                        List.of(CognitiveStageKind.PERCEPTION, CognitiveStageKind.ACTION),
                        result.stageResults().stream().map(CognitiveStageResult::kind).toList()),
                () -> assertEquals(
                        List.of(new PerceptionRequest(4, Optional.of(context))),
                        perception.receivedRequests()));
    }

    @Test
    void initialSignalsWithAPerceptionStageFailBeforeAnythingRuns() {
        var perception = new DeterministicPerceptionCapability(Map.of());
        var runtime = runtimeBuilder().perceptionCapability(perception, 2).build();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> runtime.execute(List.of(observation(1.0)))),
                () -> assertEquals(List.of(), perception.receivedRequests()));
    }

    @Test
    void aPerceptionStageAndAnotherPerceptionPositionStageAreMutuallyExclusive() {
        CognitiveStage otherPerception = new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.PERCEPTION;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
                throw new AssertionError("must not execute");
            }
        };

        assertThrows(IllegalArgumentException.class, () -> runtimeBuilder()
                .stage(otherPerception)
                .perceptionCapability(new DeterministicPerceptionCapability(Map.of()), 2)
                .build());
    }

    @Test
    void missingContextIsAnExpectedRejectionNotAnException() {
        var perception = new DeterministicPerceptionCapability(Map.of());
        var runtime = runtimeBuilder().perceptionCapability(perception, 2).build();

        var result = runtime.execute(CycleInput.of(List.of()));
        var stage = assertInstanceOf(PerceptionCognitiveStageResult.class, result.stageResults().getFirst());

        assertAll(
                () -> assertEquals(PerceptionStatus.REJECTED, stage.outcome().result().status()),
                () -> assertEquals(Optional.empty(), stage.outcome().request().hostContext()),
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()));
    }

    @Test
    void consecutiveExecutionsObserveOnlyTheirOwnHostContext() {
        var capability = new DeterministicPerceptionCapability(Map.of(
                "run-a", new Script(PerceptionStatus.SUCCEEDED, List.of(observation(1.0))),
                "run-b", new Script(PerceptionStatus.SUCCEEDED, List.of(observation(2.0)))));
        var runtime = runtimeBuilder().perceptionCapability(capability, 1).build();

        var first = runtime.execute(CycleInput.of(List.of()).withHostContext(host("run-a")));
        var second = runtime.execute(CycleInput.of(List.of()).withHostContext(host("run-b")));
        var third = runtime.execute(CycleInput.of(List.of()));

        assertAll(
                () -> assertEquals(List.of(observation(1.0)), first.outputSignals()),
                () -> assertEquals(List.of(observation(2.0)), second.outputSignals()),
                () -> assertEquals(List.of(), third.outputSignals()),
                () -> assertEquals(
                        List.of(Optional.of(host("run-a")), Optional.of(host("run-b")), Optional.empty()),
                        capability.receivedRequests().stream().map(PerceptionRequest::hostContext).toList()));
    }

    @Test
    void separateRuntimesRunInParallelWithoutSharingContext() throws Exception {
        var seen = new CopyOnWriteArrayList<String>();
        PerceptionCapability recording = request -> {
            var ref = request.hostContext().orElseThrow().executionRef().value();
            seen.add(ref);
            return new PerceptionResult(PerceptionStatus.SUCCEEDED, request.maxSignals(), List.of(observation(1.0)));
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<List<Signal>>>();
            for (var index = 0; index < 8; index++) {
                var ref = "run-" + index;
                futures.add(executor.submit(() -> runtimeBuilder().perceptionCapability(recording, 1).build()
                        .execute(CycleInput.of(List.of()).withHostContext(host(ref))).outputSignals()));
            }
            for (var future : futures) {
                assertEquals(List.of(observation(1.0)), future.get());
            }
        }

        assertEquals(
                IntStream.range(0, 8).mapToObj(index -> "run-" + index).sorted().toList(),
                seen.stream().sorted().toList());
    }

    @Test
    void adapterFailurePropagatesAsACycleFailureOfThePerceptionStage() {
        PerceptionCapability failing = request -> {
            throw new IllegalStateException("adapter exploded");
        };
        var runtime = runtimeBuilder().perceptionCapability(failing, 1).build();

        var failure = assertThrows(CognitiveCycleException.class,
                () -> runtime.execute(CycleInput.of(List.of()).withHostContext(host("run-1"))));

        assertEquals(CognitiveStageKind.PERCEPTION, failure.failedStage());
    }

    @Test
    void existingSignalDrivenRuntimesRemainSupported() {
        var runtime = runtimeBuilder().build();
        var input = List.of(observation(1.0));

        assertEquals(input, runtime.execute(input).outputSignals());
    }
}
