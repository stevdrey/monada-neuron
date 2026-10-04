package monada.neuron.host;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionCognitiveStage;
import monada.neuron.action.ActionCognitiveStageResult;
import monada.neuron.action.ActionResult;
import monada.neuron.action.ActionStatus;
import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.aeon.DeterministicAeonCoordinator;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.memory.ResonanceMemoryCognitiveStage;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.memory.ResonanceMemoryResponse;
import monada.neuron.memory.ResonanceMemoryResult;
import monada.neuron.memory.ResonanceMemoryStageResult;
import monada.neuron.memory.ResonanceMemoryStatus;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.monad.AeonCognitiveStage;
import monada.neuron.monad.CognitiveCycleException;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.monad.CognitiveCycleTermination;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageResult;
import monada.neuron.monad.CognitiveStageResultSnapshot;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeuronRuntimeTest {

    private static final CognitiveBudget BUDGET = new CognitiveBudget(100, 100, 100);

    private final PrimaryMonad monad = new PrimaryMonad(uuid(1));

    @Test
    void emptyRuntimeIsASuccessfulPassThroughForTheConfiguredMonad() {
        var runtime = NeuronRuntime.builder().monad(monad).build();
        var input = signal(1.0);

        var result = runtime.execute(List.of(input), BUDGET);

        assertEquals(uuid(1), runtime.monadId());
        assertEquals(uuid(1), result.monadId());
        assertEquals(CognitiveCycleTermination.COMPLETED, result.termination());
        assertEquals(List.of(input), result.outputSignals());
        assertTrue(result.stageResults().isEmpty());
        assertEquals(BUDGET, result.snapshot().budget());
    }

    @Test
    void executesStagesInCanonicalOrderWhateverTheRegistrationOrder() {
        var order = new ArrayList<CognitiveStageKind>();
        var runtime = NeuronRuntime.builder()
                .monad(monad)
                .stage(recording(CognitiveStageKind.EVALUATION, order))
                .stage(recording(CognitiveStageKind.PERCEPTION, order))
                .stage(recording(CognitiveStageKind.REASONING, order))
                .build();

        var result = runtime.execute(List.of(signal(1.0)), BUDGET);

        var expected = List.of(
                CognitiveStageKind.PERCEPTION,
                CognitiveStageKind.REASONING,
                CognitiveStageKind.EVALUATION);
        assertEquals(expected, order);
        assertEquals(expected, result.stageResults().stream().map(CognitiveStageResult::kind).toList());
    }

    @Test
    void memoryIsOptionalAndForwardsInputsThenRecalledSignals() {
        var recalled = signal(5.0);
        ResonanceMemoryPort port = request -> new ResonanceMemoryResponse(
                ResonanceMemoryStatus.COMPLETE,
                request.maxResults(),
                List.of(new ResonanceMemoryResult("ref-1", recalled, 0.5)));
        var input = signal(1.0);

        var without = NeuronRuntime.builder().monad(monad).build()
                .execute(List.of(input), BUDGET);
        var with = NeuronRuntime.builder().monad(monad).memoryPort(port, 4).build()
                .execute(List.of(input), BUDGET);

        assertTrue(without.stageResults().isEmpty());
        var memory = assertInstanceOf(ResonanceMemoryStageResult.class, with.stageResults().getFirst());
        assertEquals(4, memory.response().resultLimit());
        assertEquals(List.of(input, recalled), with.outputSignals());
    }

    @Test
    void actionIsOptionalAndExposesTheTypedOutcome() {
        var observation = signal(7.0);
        ActionCapability capability = request -> new ActionResult(
                ActionStatus.SUCCEEDED,
                request.maxObservations(),
                List.of(observation));
        var input = signal(1.0);

        var without = NeuronRuntime.builder().monad(monad).build()
                .execute(List.of(input), BUDGET);
        var with = NeuronRuntime.builder().monad(monad).actionCapability(capability, 2).build()
                .execute(List.of(input), BUDGET);

        assertTrue(without.stageResults().isEmpty());
        var action = assertInstanceOf(ActionCognitiveStageResult.class, with.stageResults().getFirst());
        assertEquals(ActionStatus.SUCCEEDED, action.outcome().result().status());
        assertEquals(List.of(observation), with.outputSignals());
    }

    @Test
    void memoryAndActionWireIntoTheirCanonicalPositions() {
        ResonanceMemoryPort port = request -> new ResonanceMemoryResponse(
                ResonanceMemoryStatus.COMPLETE, request.maxResults(), List.of());
        ActionCapability capability = request -> new ActionResult(
                ActionStatus.SUCCEEDED, request.maxObservations(), List.of());
        var runtime = NeuronRuntime.builder()
                .monad(monad)
                .actionCapability(capability, 1)
                .memoryPort(port, 1)
                .build();

        var result = runtime.execute(List.of(signal(1.0)), BUDGET);

        assertEquals(
                List.of(CognitiveStageKind.MEMORY_RECALL, CognitiveStageKind.ACTION),
                result.stageResults().stream().map(CognitiveStageResult::kind).toList());
    }

    @Test
    void rejectsInvalidConfigurationWhenBuilding() {
        ResonanceMemoryPort port = request -> new ResonanceMemoryResponse(
                ResonanceMemoryStatus.COMPLETE, request.maxResults(), List.of());
        ActionCapability capability = request -> new ActionResult(
                ActionStatus.SUCCEEDED, request.maxObservations(), List.of());
        var builder = NeuronRuntime.builder();

        assertThrows(NullPointerException.class, () -> NeuronRuntime.builder().monad(null));
        assertThrows(IllegalStateException.class, builder::build);
        assertThrows(NullPointerException.class, () -> NeuronRuntime.builder().stage(null));
        assertThrows(NullPointerException.class, () -> NeuronRuntime.builder().defaultBudget(null));
        assertThrows(NullPointerException.class, () -> NeuronRuntime.builder().memoryPort(null, 1));
        assertThrows(IllegalArgumentException.class, () -> NeuronRuntime.builder().memoryPort(port, 0));
        assertThrows(NullPointerException.class, () -> NeuronRuntime.builder().actionCapability(null, 1));
        assertThrows(IllegalArgumentException.class, () -> NeuronRuntime.builder().actionCapability(capability, 0));
    }

    @Test
    void rejectsTwoStagesForTheSamePosition() {
        ResonanceMemoryPort port = request -> new ResonanceMemoryResponse(
                ResonanceMemoryStatus.COMPLETE, request.maxResults(), List.of());
        ActionCapability capability = request -> new ActionResult(
                ActionStatus.SUCCEEDED, request.maxObservations(), List.of());

        assertThrows(IllegalArgumentException.class, () -> NeuronRuntime.builder()
                .monad(monad)
                .stage(new ResonanceMemoryCognitiveStage(port, 1))
                .memoryPort(port, 1)
                .build());
        assertThrows(IllegalArgumentException.class, () -> NeuronRuntime.builder()
                .monad(monad)
                .stage(new ActionCognitiveStage(capability, 1))
                .actionCapability(capability, 1)
                .build());
        assertThrows(IllegalArgumentException.class, () -> NeuronRuntime.builder()
                .monad(monad)
                .stage(recording(CognitiveStageKind.REASONING, new ArrayList<>()))
                .stage(recording(CognitiveStageKind.REASONING, new ArrayList<>()))
                .build());
    }

    @Test
    void rejectsAnAeonStageBoundToANonCanonicalAeonWhenBuilding() {
        var start = node(10);
        var foreign = new Aeon(uuid(20), AeonPurpose.PERCEPTION);
        foreign.addMember(start);
        var stage = new AeonCognitiveStage(
                CognitiveStageKind.PERCEPTION,
                foreign,
                start.getId(),
                new DeterministicAeonCoordinator(new DeterministicSignalPropagationEngine()),
                (node, signal) -> NodeProcessingResult.noOutput(),
                PropagationConfig.routeAll(1, 0));

        var builder = NeuronRuntime.builder().monad(monad).stage(stage);

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void usesTheDefaultBudgetAndLetsACallOverrideIt() {
        var defaultBudget = new CognitiveBudget(10, 20, 30);
        var override = new CognitiveBudget(11, 21, 31);
        var runtime = NeuronRuntime.builder().monad(monad).defaultBudget(defaultBudget).build();

        assertEquals(defaultBudget, runtime.execute(List.of(signal(1.0))).snapshot().budget());
        assertEquals(override, runtime.execute(List.of(signal(1.0)), override).snapshot().budget());
    }

    @Test
    void executingWithoutAnyBudgetFailsClearly() {
        var runtime = NeuronRuntime.builder().monad(monad).build();

        var failure = assertThrows(
                IllegalStateException.class,
                () -> runtime.execute(List.of(signal(1.0))));

        assertTrue(failure.getMessage().contains("budget"));
    }

    @Test
    void surfacesBudgetTerminationUntouched() {
        var tight = new CognitiveBudget(1, 1, 1);
        var stage = recording(CognitiveStageKind.PERCEPTION, new ArrayList<>());
        var runtime = NeuronRuntime.builder().monad(monad).stage(stage).build();

        var viaRuntime = runtime.execute(List.of(signal(1.0)), tight);
        var direct = new DeterministicCognitiveCycle(List.of(stage))
                .execute(monad, List.of(signal(1.0)), tight);

        assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED, viaRuntime.termination());
        assertEquals(CognitiveCycleOutcome.SUCCESS, viaRuntime.snapshot().outcome());
        assertEquals(direct, viaRuntime);
    }

    @Test
    void propagatesStageFailuresAsCycleExceptions() {
        var cause = new IllegalStateException("boom");
        var completed = recording(CognitiveStageKind.PERCEPTION, new ArrayList<>());
        var failing = new StubStage(CognitiveStageKind.REASONING, input -> {
            throw cause;
        });
        var runtime = NeuronRuntime.builder().monad(monad).stage(failing).stage(completed).build();

        var failure = assertThrows(
                CognitiveCycleException.class,
                () -> runtime.execute(List.of(signal(1.0)), BUDGET));

        assertEquals(CognitiveStageKind.REASONING, failure.failedStage());
        assertEquals(cause, failure.getCause());
        assertEquals(CognitiveCycleOutcome.FAILURE, failure.snapshot().outcome());
        assertEquals(
                List.of(CognitiveStageKind.PERCEPTION),
                failure.completedStageResults().stream().map(CognitiveStageResult::kind).toList());
    }

    @Test
    void reusesOneCompositionAcrossIndependentExecutions() {
        var stage = recording(CognitiveStageKind.PERCEPTION, new ArrayList<>());
        var runtime = NeuronRuntime.builder().monad(monad).stage(stage).defaultBudget(BUDGET).build();
        var first = signal(1.0);
        var second = signal(2.0);

        var one = runtime.execute(List.of(first));
        var two = runtime.execute(List.of(second));
        var again = runtime.execute(List.of(first));

        assertEquals(List.of(first), one.outputSignals());
        assertEquals(List.of(second), two.outputSignals());
        assertNotSame(one, again);
        assertEquals(one, again);
        assertEquals(
                new DeterministicCognitiveCycle(List.of(stage)).execute(monad, List.of(first), BUDGET),
                one);
    }

    private CognitiveStage recording(CognitiveStageKind kind, List<CognitiveStageKind> order) {
        return new StubStage(kind, input -> {
            order.add(kind);
            return input;
        });
    }

    private Node node(long id) {
        return new Node.Builder().id(uuid(id)).type(NodeType.PROCESSOR).build();
    }

    private Signal signal(double amplitude) {
        return new Signal(SignalKind.INTERMEDIATE, new FrequencyState(amplitude, 10.0, 0.0));
    }

    private UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private record StubStage(
            CognitiveStageKind kind,
            Function<List<Signal>, List<Signal>> behavior) implements CognitiveStage {

        @Override
        public CognitiveStageResult execute(
                PrimaryMonad monad,
                List<Signal> inputSignals,
                CognitiveContext context) {
            return new CognitiveStageResultSnapshot(
                    kind,
                    CognitiveStageStatus.COMPLETED,
                    behavior.apply(inputSignals));
        }
    }
}
