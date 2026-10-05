package monada.neuron.host;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionCognitiveStage;
import monada.neuron.action.ActionOutcome;
import monada.neuron.action.ActionCognitiveStageResult;
import monada.neuron.action.ActionRequest;
import monada.neuron.action.ActionResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.HostExecutionContext;
import monada.neuron.context.HostReference;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.DeterministicOutcomeFeedbackPolicy;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostContextRuntimeTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(100, 100, 100);

    private static HostExecutionContext host(String ref) {
        return HostExecutionContext.of(new HostReference(ref)).withLookupRef(new HostReference("lookup-" + ref));
    }

    private static Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }

    /** Capability that records the host context of every request it receives, as an adapter would. */
    private static final class RecordingCapability implements ActionCapability {
        final ConcurrentLinkedQueue<Optional<HostExecutionContext>> seen = new ConcurrentLinkedQueue<>();

        @Override
        public ActionResult execute(ActionRequest request) {
            seen.add(request.hostContext());
            return new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(), List.of());
        }
    }

    private static NeuronRuntime runtime(UUID monadId, ActionCapability capability) {
        return NeuronRuntime.builder()
                .monad(new PrimaryMonad(monadId))
                .actionCapability(capability, 1)
                .defaultBudget(BUDGET)
                .build();
    }

    @Test
    void actionCapabilityReceivesTheHostContextOfTheExecution() {
        var capability = new RecordingCapability();
        var runtime = runtime(new UUID(0L, 1L), capability);
        var context = host("run-1");

        var result = runtime.execute(CycleInput.of(List.of(signal(1.0))).withHostContext(context));

        var stage = assertInstanceOf(ActionCognitiveStageResult.class, result.stageResults().getFirst());
        assertAll(
                () -> assertEquals(List.of(Optional.of(context)), List.copyOf(capability.seen)),
                () -> assertEquals(Optional.of(context), stage.outcome().request().hostContext()));
    }

    @Test
    void executionWithoutHostContextStillWorksAndRequestsCarryNone() {
        var capability = new RecordingCapability();
        var runtime = runtime(new UUID(0L, 1L), capability);

        runtime.execute(List.of(signal(1.0)));
        runtime.execute(List.of(signal(1.0)), BUDGET);

        assertEquals(List.of(Optional.empty(), Optional.empty()), List.copyOf(capability.seen));
    }

    @Test
    void lowLevelCycleRemainsUsableWithoutHostContext() {
        var capability = new RecordingCapability();
        var monad = new PrimaryMonad(new UUID(0L, 1L));
        var cycle = new DeterministicCognitiveCycle(List.of(new ActionCognitiveStage(capability, 1)));

        cycle.execute(monad, List.of(signal(1.0)), BUDGET);
        cycle.execute(monad, List.of(signal(1.0)), BUDGET, Optional.of(host("low")));

        assertEquals(List.of(Optional.empty(), Optional.of(host("low"))), List.copyOf(capability.seen));
    }

    @Test
    void sequentialInterleavedExecutionsOnOneRuntimeNeverLeakContext() {
        var capability = new RecordingCapability();
        var runtime = runtime(new UUID(0L, 1L), capability);
        var a = host("a");
        var b = host("b");

        runtime.execute(CycleInput.of(List.of(signal(1.0))).withHostContext(a));
        runtime.execute(List.of(signal(1.0)));
        runtime.execute(CycleInput.of(List.of(signal(1.0))).withHostContext(b));
        runtime.execute(CycleInput.of(List.of(signal(1.0))).withHostContext(a));

        assertEquals(
                List.of(Optional.of(a), Optional.empty(), Optional.of(b), Optional.of(a)),
                List.copyOf(capability.seen));
    }

    @Test
    void concurrentExecutionsSharingOneCapabilityEachSeeOnlyTheirOwnContext() throws Exception {
        var executions = 64;
        var observed = new ConcurrentHashMap<String, Optional<HostExecutionContext>>();
        ActionCapability shared = request -> {
            // The adapter resolves the host reference from the request alone, never from ambient state.
            var context = request.hostContext().orElseThrow();
            observed.put(context.executionRef().value(), request.hostContext());
            return new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(), List.of());
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (var i = 0; i < executions; i++) {
                var index = i;
                futures.add(executor.submit(() -> {
                    var own = runtime(new UUID(0L, index + 1L), shared);
                    for (var round = 0; round < 5; round++) {
                        var context = host("run-" + index);
                        var result = own.execute(CycleInput.of(List.of(signal(1.0))).withHostContext(context));
                        var stage = (ActionCognitiveStageResult) result.stageResults().getFirst();
                        ActionOutcome outcome = stage.outcome();
                        assertEquals(Optional.of(context), outcome.request().hostContext());
                    }
                    return null;
                }));
            }
            for (var future : futures) {
                future.get();
            }
        }

        assertEquals(executions, observed.size());
        for (var i = 0; i < executions; i++) {
            assertEquals(Optional.of(host("run-" + i)), observed.get("run-" + i));
        }
    }

    @Test
    void hostContextCombinesWithPriorFeedbackAndStaysOutOfTheSnapshot() {
        var target = new Node.Builder()
                .id(new UUID(0L, 30L))
                .type(NodeType.PROCESSOR)
                .frequencyState(new FrequencyState(2.0, 10.0, 0.0))
                .energy(1.0)
                .build();
        var capability = new RecordingCapability();
        var monad = new PrimaryMonad(new UUID(0L, 1L));
        var runtime = NeuronRuntime.builder()
                .monad(monad)
                .feedbackAdaptation(new DeterministicBaselineAdaptationPolicy(), List.of(target))
                .actionCapability(capability, 1)
                .defaultBudget(BUDGET)
                .build();
        var feedback = new DeterministicOutcomeFeedbackPolicy()
                .derive(runtime.execute(List.of(signal(1.0))), List.of(target.getId()), 0)
                .orElseThrow();
        capability.seen.clear();
        var context = host("with-feedback");

        var result = runtime.execute(
                CycleInput.of(List.of(signal(1.0))).withPriorFeedback(feedback).withHostContext(context));

        assertAll(
                () -> assertEquals(List.of(Optional.of(context)), List.copyOf(capability.seen)),
                () -> assertEquals(1, target.getHistorySize()),
                () -> assertFalse(result.snapshot().toString().contains("with-feedback")));
    }

    @Test
    void missingContextIsAnExpectedRejectionByAnAdapterThatRequiresIt() {
        ActionCapability requiring = request -> request.hostContext().isPresent()
                ? new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(), List.of())
                : new ActionResult(ActionStatus.REJECTED, request.maxObservations(), List.of());
        var runtime = runtime(new UUID(0L, 1L), requiring);

        var missing = runtime.execute(List.of(signal(1.0)));
        var present = runtime.execute(CycleInput.of(List.of(signal(1.0))).withHostContext(host("x")));

        assertAll(
                () -> assertEquals(ActionStatus.REJECTED, status(missing)),
                () -> assertEquals(ActionStatus.SUCCEEDED, status(present)));
    }

    @Test
    void invalidReferencesFailBeforeAnyCycleRuns() {
        assertThrows(IllegalArgumentException.class, () -> host(" "));
    }

    private static ActionStatus status(monada.neuron.monad.CognitiveCycleResult result) {
        return ((ActionCognitiveStageResult) result.stageResults().getFirst()).outcome().result().status();
    }
}
