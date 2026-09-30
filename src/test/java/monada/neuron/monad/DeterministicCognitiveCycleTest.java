package monada.neuron.monad;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.aeon.CognitiveAeonCoordinator;
import monada.neuron.aeon.DeterministicAeonCoordinator;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.context.CognitiveTraceEvent;
import monada.neuron.evolution.AdaptationCognitiveStage;
import monada.neuron.evolution.AdaptationCognitiveStageResult;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.NoOpAdaptationPolicy;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisSetBuilder;
import monada.neuron.reasoning.Proposition;
import monada.neuron.reasoning.ReasoningCognitiveStageResult;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicCognitiveCycleTest {

    private final CognitiveAeonCoordinator coordinator = new DeterministicAeonCoordinator(
            new DeterministicSignalPropagationEngine());

    @Test
    void executesConfiguredAeonsInCanonicalOrderAndHandsOffOrderedEmissions() {
        var perceptionRoot = node(1);
        var reasoningRoot = node(2);
        var perception = aeon(10, AeonPurpose.PERCEPTION, perceptionRoot);
        var reasoning = aeon(20, AeonPurpose.REASONING, reasoningRoot);
        var monad = monad(perception, reasoning);
        var executionOrder = new ArrayList<CognitiveStageKind>();
        var intermediate = signal(2.0);
        var output = signal(3.0);

        var perceptionStage = stage(
                CognitiveStageKind.PERCEPTION,
                perception,
                perceptionRoot.getId(),
                (node, input) -> {
                    executionOrder.add(CognitiveStageKind.PERCEPTION);
                    return new NodeProcessingResult(List.of(intermediate));
                },
                3,
                0);
        var reasoningStage = stage(
                CognitiveStageKind.REASONING,
                reasoning,
                reasoningRoot.getId(),
                (node, input) -> {
                    executionOrder.add(CognitiveStageKind.REASONING);
                    assertEquals(intermediate, input);
                    return new NodeProcessingResult(List.of(output));
                },
                3,
                0);

        var result = new DeterministicCognitiveCycle(List.of(reasoningStage, perceptionStage)).execute(
                monad,
                List.of(signal(1.0)),
                new CognitiveBudget(10, 10, 30));
        var stageStarts = result.snapshot().traceEntries().stream()
                .map(entry -> entry.event())
                .filter(CognitiveTraceEvent.CognitiveStageStarted.class::isInstance)
                .map(CognitiveTraceEvent.CognitiveStageStarted.class::cast)
                .map(CognitiveTraceEvent.CognitiveStageStarted::stage)
                .toList();

        assertAll(
                () -> assertEquals(List.of(
                        CognitiveStageKind.PERCEPTION,
                        CognitiveStageKind.REASONING), executionOrder),
                () -> assertEquals(List.of(
                        CognitiveStageKind.PERCEPTION,
                        CognitiveStageKind.REASONING), result.stageResults().stream()
                        .map(CognitiveStageResult::kind)
                        .toList()),
                () -> assertInstanceOf(AeonCognitiveStageResult.class,
                        result.stageResults().getFirst()),
                () -> assertEquals(List.of(output), result.outputSignals()),
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertEquals(List.of(
                        CognitiveStageKind.PERCEPTION,
                        CognitiveStageKind.REASONING), stageStarts),
                () -> assertEquals(CognitiveCycleOutcome.SUCCESS, result.snapshot().outcome()));
    }

    @Test
    void emptyPlanPassesSignalsThroughAndEmptyInputStopsBeforeTheFirstStage() {
        var input = signal(1.0);
        var monad = new PrimaryMonad(uuid(1));

        var passThrough = new DeterministicCognitiveCycle(List.of()).execute(
                monad,
                List.of(input),
                new CognitiveBudget(2, 2, 2));

        var root = node(2);
        var perception = aeon(10, AeonPurpose.PERCEPTION, root);
        var emptyInput = new DeterministicCognitiveCycle(List.of(stage(
                CognitiveStageKind.PERCEPTION,
                perception,
                root.getId(),
                (node, received) -> NodeProcessingResult.noOutput(),
                1,
                0))).execute(
                monad(perception),
                List.of(),
                new CognitiveBudget(2, 2, 10));

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, passThrough.termination()),
                () -> assertEquals(List.of(input), passThrough.outputSignals()),
                () -> assertTrue(passThrough.stageResults().isEmpty()),
                () -> assertEquals(0, passThrough.snapshot().acceptedSignals()),
                () -> assertEquals(CognitiveCycleTermination.NO_SIGNALS, emptyInput.termination()),
                () -> assertTrue(emptyInput.stageResults().isEmpty()),
                () -> assertTrue(emptyInput.snapshot().traceEntries().isEmpty()));
    }

    @Test
    void emptyIntermediateOutputStopsBeforeTheNextConfiguredStage() {
        var perceptionRoot = node(1);
        var reasoningRoot = node(2);
        var perception = aeon(10, AeonPurpose.PERCEPTION, perceptionRoot);
        var reasoning = aeon(20, AeonPurpose.REASONING, reasoningRoot);
        var reasoningCalls = new AtomicInteger();

        var result = new DeterministicCognitiveCycle(List.of(
                stage(
                        CognitiveStageKind.PERCEPTION,
                        perception,
                        perceptionRoot.getId(),
                        (node, input) -> NodeProcessingResult.noOutput(),
                        1,
                        0),
                stage(
                        CognitiveStageKind.REASONING,
                        reasoning,
                        reasoningRoot.getId(),
                        (node, input) -> {
                            reasoningCalls.incrementAndGet();
                            return NodeProcessingResult.noOutput();
                        },
                        1,
                        0))).execute(
                monad(perception, reasoning),
                List.of(signal(1.0)),
                new CognitiveBudget(4, 4, 20));

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.NO_SIGNALS, result.termination()),
                () -> assertEquals(1, result.stageResults().size()),
                () -> assertEquals(CognitiveStageKind.PERCEPTION, result.stageResults().getFirst().kind()),
                () -> assertEquals(0, reasoningCalls.get()));
    }

    @Test
    void validatesAllAeonBindingsBeforeAnyProcessorWork() {
        var root = node(1);
        var canonical = aeon(10, AeonPurpose.PERCEPTION, root);
        var duplicate = aeon(10, AeonPurpose.PERCEPTION, root);
        var calls = new AtomicInteger();
        var stage = stage(
                CognitiveStageKind.PERCEPTION,
                duplicate,
                root.getId(),
                (node, input) -> {
                    calls.incrementAndGet();
                    return NodeProcessingResult.noOutput();
                },
                1,
                0);
        var cycle = new DeterministicCognitiveCycle(List.of(stage));

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> cycle.execute(
                        monad(canonical),
                        List.of(signal(1.0)),
                        new CognitiveBudget(2, 2, 2)));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("canonical")),
                () -> assertEquals(0, calls.get()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DeterministicCognitiveCycle(List.of(stage, stage))));
    }

    @Test
    void rejectsPurposeMismatchBeforeCreatingCycleWork() {
        var root = node(1);
        var perception = aeon(10, AeonPurpose.PERCEPTION, root);
        var calls = new AtomicInteger();
        var cycle = new DeterministicCognitiveCycle(List.of(stage(
                CognitiveStageKind.REASONING,
                perception,
                root.getId(),
                (node, input) -> {
                    calls.incrementAndGet();
                    return NodeProcessingResult.noOutput();
                },
                1,
                0)));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> cycle.execute(
                                monad(perception),
                                List.of(signal(1.0)),
                                new CognitiveBudget(2, 2, 2))),
                () -> assertEquals(0, calls.get()));
    }

    @Test
    void rejectsAnUnregisteredAeonBeforeAnyProcessorWork() {
        var root = node(1);
        var unregistered = aeon(10, AeonPurpose.PERCEPTION, root);
        var calls = new AtomicInteger();
        var cycle = new DeterministicCognitiveCycle(List.of(stage(
                CognitiveStageKind.PERCEPTION,
                unregistered,
                root.getId(),
                (node, input) -> {
                    calls.incrementAndGet();
                    return NodeProcessingResult.noOutput();
                },
                1,
                0)));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> cycle.execute(
                                new PrimaryMonad(uuid(100)),
                                List.of(signal(1.0)),
                                new CognitiveBudget(2, 2, 2))),
                () -> assertEquals(0, calls.get()));
    }

    @Test
    void rejectsInvalidStageResultsWithoutExecutingLaterStages() {
        var laterStageExecuted = new AtomicBoolean();
        CognitiveStage invalid = new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.PERCEPTION;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad,
                    List<Signal> inputSignals,
                    CognitiveContext context) {
                return new TestStageResult(
                        CognitiveStageKind.REASONING,
                        CognitiveStageStatus.COMPLETED,
                        inputSignals);
            }
        };
        CognitiveStage later = new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.REASONING;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad,
                    List<Signal> inputSignals,
                    CognitiveContext context) {
                laterStageExecuted.set(true);
                return new TestStageResult(kind(), CognitiveStageStatus.COMPLETED, inputSignals);
            }
        };

        var failure = assertThrows(
                CognitiveCycleException.class,
                () -> new DeterministicCognitiveCycle(List.of(later, invalid)).execute(
                        new PrimaryMonad(uuid(1)),
                        List.of(signal(1.0)),
                        new CognitiveBudget(2, 2, 10)));

        assertAll(
                () -> assertEquals(CognitiveStageKind.PERCEPTION, failure.failedStage()),
                () -> assertEquals(CognitiveCycleOutcome.FAILURE, failure.snapshot().outcome()),
                () -> assertFalse(laterStageExecuted.get()));
    }

    @Test
    void failurePreservesTheCauseFailureSnapshotAndCompletedStagePrefix() {
        var perceptionRoot = node(1);
        var reasoningRoot = node(2);
        var perception = aeon(10, AeonPurpose.PERCEPTION, perceptionRoot);
        var reasoning = aeon(20, AeonPurpose.REASONING, reasoningRoot);
        var originalFailure = new IllegalStateException("reasoning failed");
        var laterCalls = new AtomicInteger();

        var failure = assertThrows(
                CognitiveCycleException.class,
                () -> new DeterministicCognitiveCycle(List.of(
                        stage(
                                CognitiveStageKind.PERCEPTION,
                                perception,
                                perceptionRoot.getId(),
                                (node, input) -> new NodeProcessingResult(List.of(signal(2.0))),
                                2,
                                0),
                        stage(
                                CognitiveStageKind.REASONING,
                                reasoning,
                                reasoningRoot.getId(),
                                (node, input) -> {
                                    laterCalls.incrementAndGet();
                                    throw originalFailure;
                                },
                                2,
                                0))).execute(
                        monad(perception, reasoning),
                        List.of(signal(1.0)),
                        new CognitiveBudget(6, 8, 30)));

        assertAll(
                () -> assertSame(originalFailure, failure.getCause()),
                () -> assertEquals(CognitiveStageKind.REASONING, failure.failedStage()),
                () -> assertEquals(1, failure.completedStageResults().size()),
                () -> assertEquals(CognitiveCycleOutcome.FAILURE, failure.snapshot().outcome()),
                () -> assertEquals(1, laterCalls.get()),
                () -> assertTrue(failure.snapshot().traceEntries().stream()
                        .map(entry -> entry.event())
                        .anyMatch(CognitiveTraceEvent.CognitiveStageFailed.class::isInstance)));
    }

    @Test
    void globalBudgetExhaustionStopsLaterStagesAndTakesPriorityOverLocalTruncation() {
        var root = node(1);
        var child = node(2);
        root.connect(child);
        var perception = aeon(10, AeonPurpose.PERCEPTION, root, child);
        var reasoningRoot = node(3);
        var reasoning = aeon(20, AeonPurpose.REASONING, reasoningRoot);
        var reasoningCalls = new AtomicInteger();

        var globalResult = new DeterministicCognitiveCycle(List.of(
                stage(
                        CognitiveStageKind.PERCEPTION,
                        perception,
                        root.getId(),
                        (node, input) -> node.getId().equals(root.getId())
                                ? new NodeProcessingResult(List.of(input))
                                : NodeProcessingResult.noOutput(),
                        3,
                        1),
                stage(
                        CognitiveStageKind.REASONING,
                        reasoning,
                        reasoningRoot.getId(),
                        (node, input) -> {
                            reasoningCalls.incrementAndGet();
                            return NodeProcessingResult.noOutput();
                        },
                        1,
                        0))).execute(
                monad(perception, reasoning),
                List.of(signal(1.0)),
                new CognitiveBudget(1, 5, 30));

        var priorityResult = new DeterministicCognitiveCycle(List.of(stage(
                CognitiveStageKind.PERCEPTION,
                perception,
                root.getId(),
                (node, input) -> new NodeProcessingResult(List.of(signal(2.0), signal(3.0))),
                3,
                0))).execute(
                monad(perception),
                List.of(signal(1.0)),
                new CognitiveBudget(5, 2, 30));

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED,
                        globalResult.termination()),
                () -> assertTrue(globalResult.snapshot().stepBudgetExhausted()),
                () -> assertEquals(0, reasoningCalls.get()),
                () -> assertEquals(CognitiveCycleTermination.CONTEXT_BUDGET_EXHAUSTED,
                        priorityResult.termination()),
                () -> assertTrue(priorityResult.snapshot().signalBudgetExhausted()),
                () -> assertEquals(CognitiveStageStatus.LIMIT_REACHED,
                        priorityResult.stageResults().getFirst().status()));
    }

    @Test
    void stageStepLimitStopsTheCycleWithoutExhaustingTheGlobalBudget() {
        var root = node(1);
        var child = node(2);
        root.connect(child);
        var perception = aeon(10, AeonPurpose.PERCEPTION, root, child);
        var result = new DeterministicCognitiveCycle(List.of(stage(
                CognitiveStageKind.PERCEPTION,
                perception,
                root.getId(),
                (node, input) -> node.getId().equals(root.getId())
                        ? new NodeProcessingResult(List.of(input))
                        : NodeProcessingResult.noOutput(),
                1,
                1))).execute(
                monad(perception),
                List.of(signal(1.0)),
                new CognitiveBudget(5, 8, 30));

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.STAGE_LIMIT_REACHED, result.termination()),
                () -> assertEquals(CognitiveStageStatus.LIMIT_REACHED,
                        result.stageResults().getFirst().status()),
                () -> assertFalse(result.snapshot().stepBudgetExhausted()),
                () -> assertEquals(1, result.snapshot().processedSteps()));
    }

    @Test
    void stageHopLimitStopsTheCycleWithoutExhaustingTheGlobalBudget() {
        var root = node(1);
        var child = node(2);
        root.connect(child);
        var perception = aeon(10, AeonPurpose.PERCEPTION, root, child);
        var result = new DeterministicCognitiveCycle(List.of(stage(
                CognitiveStageKind.PERCEPTION,
                perception,
                root.getId(),
                (node, input) -> new NodeProcessingResult(List.of(input)),
                3,
                0))).execute(
                monad(perception),
                List.of(signal(1.0)),
                new CognitiveBudget(5, 8, 30));

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.STAGE_LIMIT_REACHED, result.termination()),
                () -> assertEquals(CognitiveStageStatus.LIMIT_REACHED,
                        result.stageResults().getFirst().status()),
                () -> assertFalse(result.snapshot().stepBudgetExhausted()),
                () -> assertFalse(result.snapshot().signalBudgetExhausted()),
                () -> assertEquals(1, result.snapshot().processedSteps()));
    }

    @Test
    void deterministicReplayPreservesCompleteCycleResultAndTrace() {
        var perceptionRoot = node(1);
        var reasoningRoot = node(2);
        var perception = aeon(10, AeonPurpose.PERCEPTION, perceptionRoot);
        var reasoning = aeon(20, AeonPurpose.REASONING, reasoningRoot);
        var cycle = new DeterministicCognitiveCycle(List.of(
                stage(
                        CognitiveStageKind.PERCEPTION,
                        perception,
                        perceptionRoot.getId(),
                        (node, input) -> new NodeProcessingResult(List.of(signal(2.0))),
                        2,
                        0),
                stage(
                        CognitiveStageKind.REASONING,
                        reasoning,
                        reasoningRoot.getId(),
                        (node, input) -> new NodeProcessingResult(List.of(signal(3.0))),
                        2,
                        0)));
        var monad = monad(perception, reasoning);

        var first = cycle.execute(monad, List.of(signal(1.0)), new CognitiveBudget(8, 8, 30));
        var second = cycle.execute(monad, List.of(signal(1.0)), new CognitiveBudget(8, 8, 30));

        assertEquals(first, second);
    }

    private AeonCognitiveStage stage(
            CognitiveStageKind kind,
            Aeon aeon,
            UUID startNodeId,
            NodeProcessor processor,
            int maxSteps,
            int maxHops) {
        return new AeonCognitiveStage(
                kind,
                aeon,
                startNodeId,
                coordinator,
                processor,
                PropagationConfig.routeAll(maxSteps, maxHops));
    }

    private PrimaryMonad monad(Aeon... aeons) {
        var monad = new PrimaryMonad(uuid(100));
        for (var aeon : aeons) {
            monad.registerAeon(aeon);
        }
        return monad;
    }

    private Aeon aeon(long id, AeonPurpose purpose, Node... nodes) {
        var aeon = new Aeon(uuid(id), purpose);
        for (var node : nodes) {
            aeon.addMember(node);
        }
        return aeon;
    }

    private Node node(long id) {
        return new Node.Builder().id(uuid(id)).type(NodeType.PROCESSOR).build();
    }

    private Signal signal(double amplitude) {
        return new Signal(
                SignalKind.INTERMEDIATE,
                new FrequencyState(amplitude, 10.0, 0.0));
    }

    private UUID uuid(long value) {
        return new UUID(0L, value);
    }

    @Test
    void demonstratesABExecutionWithAdaptationDisabledVsEnabled() {
        var perceptionRootA = node(1);
        var perceptionA = aeon(10, AeonPurpose.PERCEPTION, perceptionRootA);
        var monadA = monad(perceptionA);

        var targetNodeA = node(3);
        var noOpStage = new AdaptationCognitiveStage(NoOpAdaptationPolicy.INSTANCE, List.of(targetNodeA));
        var perceptionStageA = stage(
                CognitiveStageKind.PERCEPTION,
                perceptionA,
                perceptionRootA.getId(),
                (n, sig) -> new NodeProcessingResult(List.of(new Signal(SignalKind.FEEDBACK, new FrequencyState(5.0, 50.0, 1.0)))),
                3,
                0);

        var resultA = new DeterministicCognitiveCycle(List.of(perceptionStageA, noOpStage)).execute(
                monadA,
                List.of(signal(1.0)),
                new CognitiveBudget(10, 10, 30));

        // In A (no-op), target node is untouched
        assertEquals(FrequencyState.ZERO, targetNodeA.getFrequencyState());
        assertEquals(0.0, targetNodeA.getEnergy());
        assertTrue(targetNodeA.getHistory().isEmpty());
        assertEquals(CognitiveCycleTermination.COMPLETED, resultA.termination());

        // In B (baseline adaptation enabled), identical structure adapts target node
        var perceptionRootB = node(1);
        var perceptionB = aeon(10, AeonPurpose.PERCEPTION, perceptionRootB);
        var monadB = monad(perceptionB);

        var targetNodeB = node(3);
        var baselineStage = new AdaptationCognitiveStage(new DeterministicBaselineAdaptationPolicy(), List.of(targetNodeB));
        var perceptionStageB = stage(
                CognitiveStageKind.PERCEPTION,
                perceptionB,
                perceptionRootB.getId(),
                (n, sig) -> new NodeProcessingResult(List.of(new Signal(SignalKind.FEEDBACK, new FrequencyState(5.0, 50.0, 1.0)))),
                3,
                0);

        var resultB = new DeterministicCognitiveCycle(List.of(perceptionStageB, baselineStage)).execute(
                monadB,
                List.of(signal(1.0)),
                new CognitiveBudget(10, 10, 30));

        // In B, target node adapted and state history recorded
        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, resultB.termination()),
                () -> assertTrue(targetNodeB.getFrequencyState().amplitude() > 0.0),
                () -> assertTrue(targetNodeB.getEnergy() > 0.0),
                () -> assertEquals(1, targetNodeB.getHistory().size()),
                () -> assertEquals(FrequencyState.ZERO, targetNodeB.getHistory().getFirst()),
                () -> {
                    var adaptedEvent = resultB.snapshot().traceEntries().stream()
                            .map(entry -> entry.event())
                            .filter(CognitiveTraceEvent.NodeAdapted.class::isInstance)
                            .map(CognitiveTraceEvent.NodeAdapted.class::cast)
                            .findFirst()
                            .orElseThrow();
                    assertEquals(targetNodeB.getId(), adaptedEvent.nodeId());
                    assertTrue(adaptedEvent.adapted());
                });
    }

    @Test
    void handsPreviousStageResultToNextStageIncludingTypedHypotheses() {
        var hypotheses = new HypothesisSetBuilder(HypothesisLimits.DEFAULT);
        hypotheses.propose(new Proposition(0, 42));
        var produced = hypotheses.build();
        var seenByReasoning = new ArrayList<Optional<CognitiveStageResult>>();
        var seenByEvaluation = new ArrayList<HypothesisSet>();
        CognitiveStage reasoning = new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.REASONING;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
                throw new AssertionError("previous-result overload must be used");
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad,
                    List<Signal> inputSignals,
                    Optional<CognitiveStageResult> previousResult,
                    CognitiveContext context) {
                seenByReasoning.add(previousResult);
                return new ReasoningCognitiveStageResult(
                        CognitiveStageStatus.COMPLETED, inputSignals, produced);
            }
        };
        CognitiveStage evaluation = new CognitiveStage() {
            @Override
            public CognitiveStageKind kind() {
                return CognitiveStageKind.EVALUATION;
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad,
                    List<Signal> inputSignals,
                    Optional<CognitiveStageResult> previousResult,
                    CognitiveContext context) {
                seenByEvaluation.add(ReasoningCognitiveStageResult.hypothesesOf(previousResult));
                return new TestStageResult(kind(), CognitiveStageStatus.COMPLETED, inputSignals);
            }

            @Override
            public CognitiveStageResult execute(
                    PrimaryMonad monad, List<Signal> inputSignals, CognitiveContext context) {
                throw new AssertionError("previous-result overload must be used");
            }
        };

        var result = new DeterministicCognitiveCycle(List.of(evaluation, reasoning)).execute(
                new PrimaryMonad(uuid(1)),
                List.of(signal(1.0)),
                new CognitiveBudget(10, 10, 30));

        assertAll(
                () -> assertEquals(CognitiveCycleTermination.COMPLETED, result.termination()),
                () -> assertEquals(List.of(Optional.empty()), seenByReasoning),
                () -> assertEquals(1, seenByEvaluation.size()),
                () -> assertEquals(produced, seenByEvaluation.getFirst()));
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
