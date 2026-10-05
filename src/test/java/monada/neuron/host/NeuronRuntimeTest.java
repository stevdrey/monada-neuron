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
import monada.neuron.context.CognitiveTraceEvent;
import monada.neuron.evolution.FeedbackDisposition;
import monada.neuron.evolution.FeedbackEntry;
import monada.neuron.evolution.OutcomeFeedback;
import monada.neuron.evolution.ScopedFeedbackAdaptationCognitiveStage;
import monada.neuron.evolution.AdaptationCognitiveStage;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.DeterministicOutcomeFeedbackPolicy;
import monada.neuron.evolution.FeedbackAdaptationCognitiveStage;
import monada.neuron.evolution.NoOpAdaptationPolicy;
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
import java.util.Optional;
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

    @Test
    void configuredOnceConsumesFreshPriorFeedbackOnEachExecution() {
        var scopedTarget = feedbackTarget();
        var capturingTarget = feedbackTarget();
        var policy = new DeterministicOutcomeFeedbackPolicy();
        var runtime = NeuronRuntime.builder()
                .monad(monad)
                .feedbackAdaptation(new DeterministicBaselineAdaptationPolicy(), List.of(scopedTarget))
                .actionCapability(succeeding(), 1)
                .defaultBudget(BUDGET)
                .build();

        var first = runtime.execute(CycleInput.of(List.of(signal(1.0))));
        var firstFeedback = policy.derive(first, List.of(scopedTarget.getId()), 0).orElseThrow();
        var second = runtime.execute(CycleInput.of(List.of(signal(1.0))).withPriorFeedback(firstFeedback));
        var secondFeedback = policy.derive(second, List.of(scopedTarget.getId()), 1).orElseThrow();
        var third = runtime.execute(CycleInput.of(List.of(signal(1.0))).withPriorFeedback(secondFeedback));
        var fourth = runtime.execute(CycleInput.of(List.of(signal(1.0))));

        // The per-cycle capturing approach of ADR 0021 is the reference for the same sequence.
        var reference = new ArrayList<CognitiveCycleResult>();
        reference.add(directCycle(capturingTarget, Optional.empty()).execute(monad, List.of(signal(1.0)), BUDGET));
        var carried = policy.derive(reference.getLast(), List.of(capturingTarget.getId()), 0);
        reference.add(directCycle(capturingTarget, carried).execute(monad, List.of(signal(1.0)), BUDGET));
        carried = policy.derive(reference.getLast(), List.of(capturingTarget.getId()), 1);
        reference.add(directCycle(capturingTarget, carried).execute(monad, List.of(signal(1.0)), BUDGET));
        reference.add(directCycle(capturingTarget, Optional.empty()).execute(monad, List.of(signal(1.0)), BUDGET));

        assertEquals(2, scopedTarget.getHistorySize());
        assertEquals(capturingTarget.getFrequencyState(), scopedTarget.getFrequencyState());
        assertEquals(capturingTarget.getEnergy(), scopedTarget.getEnergy());
        assertEquals(reference, List.of(first, second, third, fourth));
        assertEquals(1, feedbackConsumedCount(second));
        assertEquals(1, feedbackConsumedCount(third));
        assertEquals(0, feedbackConsumedCount(first));
        assertEquals(0, feedbackConsumedCount(fourth));
    }

    @Test
    void feedbackLeavesNoBindingAfterTheExecutionEnds() {
        var target = feedbackTarget();
        var runtime = NeuronRuntime.builder()
                .monad(monad)
                .feedbackAdaptation(new DeterministicBaselineAdaptationPolicy(), List.of(target))
                .actionCapability(succeeding(), 1)
                .defaultBudget(BUDGET)
                .build();
        var feedback = new DeterministicOutcomeFeedbackPolicy()
                .derive(runtime.execute(List.of(signal(1.0))), List.of(target.getId()), 0)
                .orElseThrow();

        runtime.execute(CycleInput.of(List.of(signal(1.0))).withPriorFeedback(feedback));
        runtime.execute(List.of(signal(1.0)));
        runtime.execute(List.of(signal(1.0), signal(2.0)), BUDGET);

        assertEquals(1, target.getHistorySize());
    }

    @Test
    void feedbackFromAnotherMonadFailsBeforeAnyNodeChanges() {
        var target = feedbackTarget();
        var runtime = NeuronRuntime.builder()
                .monad(monad)
                .feedbackAdaptation(new DeterministicBaselineAdaptationPolicy(), List.of(target))
                .actionCapability(succeeding(), 1)
                .defaultBudget(BUDGET)
                .build();
        var foreign = new OutcomeFeedback(
                uuid(999), 0L, ActionStatus.SUCCEEDED, 0, FeedbackDisposition.REINFORCE,
                List.of(FeedbackEntry.of(target.getId(), 1.0)), List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.execute(CycleInput.of(List.of(signal(1.0))).withPriorFeedback(foreign)));
        assertEquals(0, target.getHistorySize());
        assertEquals(new FrequencyState(2.0, 10.0, 0.0), target.getFrequencyState());
    }

    @Test
    void feedbackFailsLoudlyWhenNoFeedbackStageIsConfigured() {
        var runtime = NeuronRuntime.builder().monad(monad).defaultBudget(BUDGET).build();
        var feedback = OutcomeFeedback.neutral(uuid(1), 0L, ActionStatus.TIMED_OUT, List.of());

        var failure = assertThrows(
                IllegalStateException.class,
                () -> runtime.execute(CycleInput.of(List.of(signal(1.0))).withPriorFeedback(feedback)));

        assertTrue(failure.getMessage().contains("feedback"));
    }

    @Test
    void aGenericScopedFeedbackStageAlsoEnablesPriorFeedback() {
        var target = feedbackTarget();
        var runtime = NeuronRuntime.builder()
                .monad(monad)
                .stage(new ScopedFeedbackAdaptationCognitiveStage(
                        new DeterministicBaselineAdaptationPolicy(), List.of(target)))
                .actionCapability(succeeding(), 1)
                .defaultBudget(BUDGET)
                .build();
        var feedback = new DeterministicOutcomeFeedbackPolicy()
                .derive(runtime.execute(List.of(signal(1.0))), List.of(target.getId()), 0)
                .orElseThrow();

        runtime.execute(CycleInput.of(List.of(signal(1.0))).withPriorFeedback(feedback));

        assertEquals(1, target.getHistorySize());
    }

    @Test
    void feedbackStageCannotShareThePositionWithAnotherAdaptationStage() {
        var target = feedbackTarget();

        assertThrows(IllegalArgumentException.class, () -> NeuronRuntime.builder()
                .monad(monad)
                .stage(new AdaptationCognitiveStage(NoOpAdaptationPolicy.INSTANCE, List.of(target)))
                .feedbackAdaptation(new DeterministicBaselineAdaptationPolicy(), List.of(target))
                .build());
        assertThrows(NullPointerException.class, () -> NeuronRuntime.builder()
                .feedbackAdaptation(null, List.of(target)));
        assertThrows(NullPointerException.class, () -> NeuronRuntime.builder()
                .feedbackAdaptation(new DeterministicBaselineAdaptationPolicy(), (List<Node>) null));
        assertThrows(NullPointerException.class, () -> NeuronRuntime.builder()
                .feedbackAdaptation(new DeterministicBaselineAdaptationPolicy(), (Aeon) null));
    }

    @Test
    void feedbackAdaptationCanTargetAnAeon() {
        var member = feedbackTarget();
        var aeon = new Aeon(uuid(60), AeonPurpose.EVOLUTION);
        aeon.addMember(member);
        var runtime = NeuronRuntime.builder()
                .monad(monad)
                .feedbackAdaptation(new DeterministicBaselineAdaptationPolicy(), aeon)
                .actionCapability(succeeding(), 1)
                .defaultBudget(BUDGET)
                .build();
        var feedback = new DeterministicOutcomeFeedbackPolicy()
                .derive(runtime.execute(List.of(signal(1.0))), List.of(member.getId()), 0)
                .orElseThrow();

        runtime.execute(CycleInput.of(List.of(signal(1.0))).withPriorFeedback(feedback));

        assertEquals(1, member.getHistorySize());
    }

    @Test
    void aBudgetInTheCycleInputOverridesTheDefault() {
        var override = new CognitiveBudget(11, 21, 31);
        var runtime = NeuronRuntime.builder().monad(monad).defaultBudget(BUDGET).build();

        var result = runtime.execute(CycleInput.of(List.of(signal(1.0))).withBudget(override));

        assertEquals(override, result.snapshot().budget());
    }

    @Test
    void aCycleInputWithoutAnyBudgetFailsClearly() {
        var runtime = NeuronRuntime.builder().monad(monad).build();

        var failure = assertThrows(
                IllegalStateException.class,
                () -> runtime.execute(CycleInput.of(List.of(signal(1.0)))));

        assertTrue(failure.getMessage().contains("budget"));
    }

    private ActionCapability succeeding() {
        return request -> new ActionResult(ActionStatus.SUCCEEDED, request.maxObservations(), List.of());
    }

    private Node feedbackTarget() {
        return new Node.Builder()
                .id(uuid(30))
                .type(NodeType.PROCESSOR)
                .frequencyState(new FrequencyState(2.0, 10.0, 0.0))
                .energy(1.0)
                .build();
    }

    private DeterministicCognitiveCycle directCycle(Node target, Optional<OutcomeFeedback> prior) {
        var stages = new ArrayList<CognitiveStage>();
        stages.add(prior.isPresent()
                ? new FeedbackAdaptationCognitiveStage(
                        new DeterministicBaselineAdaptationPolicy(), List.of(target), prior.get())
                : new ScopedFeedbackAdaptationCognitiveStage(
                        new DeterministicBaselineAdaptationPolicy(), List.of(target)));
        stages.add(new ActionCognitiveStage(succeeding(), 1));
        return new DeterministicCognitiveCycle(stages);
    }

    private long feedbackConsumedCount(CognitiveCycleResult result) {
        return result.snapshot().traceEntries().stream()
                .map(entry -> entry.event())
                .filter(CognitiveTraceEvent.FeedbackConsumed.class::isInstance)
                .count();
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
