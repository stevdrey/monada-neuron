package monada.neuron.aeon;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.context.CognitiveSignalOccurrence;
import monada.neuron.context.CognitiveTraceEvent;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.SignalPropagationEngine;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedParallelAeonCoordinatorContextTest {

    private final DeterministicSignalPropagationEngine engine = new DeterministicSignalPropagationEngine();
    private final DeterministicAeonCoordinator sequentialCoordinator = new DeterministicAeonCoordinator(engine);
    private final BoundedParallelAeonCoordinator parallelCoordinator = new BoundedParallelAeonCoordinator(engine, 4, 2);

    @AfterEach
    void tearDown() {
        parallelCoordinator.close();
    }

    @Test
    void contextualEquivalenceAcrossRandomizedWorkloads() {
        var root1 = node(1);
        var child1 = node(2);
        var grandChild1 = node(3);
        root1.connect(child1);
        child1.connect(grandChild1);

        var root2 = node(4);
        var child2 = node(5);
        root2.connect(child2);

        var aeon = aeonWith(root1, child1, grandChild1, root2, child2);

        var inputs = new ArrayList<AeonInput>();
        for (int i = 0; i < 32; i++) {
            UUID startId = (i % 2 == 0) ? root1.getId() : root2.getId();
            inputs.add(new AeonInput(startId, signal(i + 1.0)));
        }

        NodeProcessor processor = (node, input) -> new NodeProcessingResult(List.of(input));
        var config = PropagationConfig.routeAll(10, 3);
        var budget = new CognitiveBudget(5_000, 5_000, 10_000);

        var seqContext = new CognitiveContext(budget);
        var seqResult = sequentialCoordinator.coordinate(aeon, inputs, processor, config, seqContext);
        var seqSnapshot = seqContext.complete(CognitiveCycleOutcome.SUCCESS);

        var parContext = new CognitiveContext(budget);
        var parResult = parallelCoordinator.coordinate(aeon, inputs, processor, config, parContext);
        var parSnapshot = parContext.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(seqResult, parResult),
                () -> assertEquals(seqSnapshot.processedSteps(), parSnapshot.processedSteps()),
                () -> assertEquals(seqSnapshot.acceptedSignals(), parSnapshot.acceptedSignals()),
                () -> assertEquals(seqSnapshot.stepBudgetExhausted(), parSnapshot.stepBudgetExhausted()),
                () -> assertEquals(seqSnapshot.signalBudgetExhausted(), parSnapshot.signalBudgetExhausted()),
                () -> assertEquals(seqSnapshot.traceBudgetExhausted(), parSnapshot.traceBudgetExhausted()),
                () -> assertEquals(seqSnapshot.signalOccurrences(), parSnapshot.signalOccurrences()),
                () -> assertEquals(seqSnapshot.aeonResults(), parSnapshot.aeonResults()),
                () -> assertEquals(seqSnapshot.traceEntries(), parSnapshot.traceEntries()),
                () -> assertEquals(seqSnapshot, parSnapshot));
    }

    @Test
    void stepBudgetExhaustionProducesExactOracleBehavior() {
        var root = node(1);
        var child = node(2);
        root.connect(child);
        var aeon = aeonWith(root, child);
        var inputs = List.of(
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)),
                new AeonInput(root.getId(), signal(3.0)));

        var budget = new CognitiveBudget(3, 20, 30);

        var seqContext = new CognitiveContext(budget);
        var seqResult = sequentialCoordinator.coordinate(
                aeon,
                inputs,
                (node, input) -> new NodeProcessingResult(List.of(input)),
                PropagationConfig.routeAll(10, 1),
                seqContext);
        var seqSnapshot = seqContext.complete(CognitiveCycleOutcome.SUCCESS);

        var parContext = new CognitiveContext(budget);
        var parResult = parallelCoordinator.coordinate(
                aeon,
                inputs,
                (node, input) -> new NodeProcessingResult(List.of(input)),
                PropagationConfig.routeAll(10, 1),
                parContext);
        var parSnapshot = parContext.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(seqResult, parResult),
                () -> assertEquals(seqSnapshot.processedSteps(), parSnapshot.processedSteps()),
                () -> assertEquals(seqSnapshot.stepBudgetExhausted(), parSnapshot.stepBudgetExhausted()),
                () -> assertEquals(seqSnapshot.signalOccurrences(), parSnapshot.signalOccurrences()),
                () -> assertEquals(seqSnapshot.aeonResults(), parSnapshot.aeonResults()),
                () -> assertEquals(seqSnapshot.traceEntries(), parSnapshot.traceEntries()),
                () -> assertEquals(seqSnapshot, parSnapshot));
    }

    @Test
    void signalBudgetExhaustionProducesExactOracleBehavior() {
        var root = node(1);
        var first = node(2);
        var second = node(3);
        root.connect(second);
        root.connect(first);
        var aeon = aeonWith(root, first, second);

        var inputs = List.of(
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));

        var budget = new CognitiveBudget(20, 6, 40);

        NodeProcessor processor = (node, input) -> node.getId().equals(root.getId())
                ? new NodeProcessingResult(List.of(input, input))
                : NodeProcessingResult.noOutput();

        var seqContext = new CognitiveContext(budget);
        var seqResult = sequentialCoordinator.coordinate(
                aeon,
                inputs,
                processor,
                PropagationConfig.routeAll(10, 1),
                seqContext);
        var seqSnapshot = seqContext.complete(CognitiveCycleOutcome.SUCCESS);

        var parContext = new CognitiveContext(budget);
        var parResult = parallelCoordinator.coordinate(
                aeon,
                inputs,
                processor,
                PropagationConfig.routeAll(10, 1),
                parContext);
        var parSnapshot = parContext.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(seqResult, parResult),
                () -> assertEquals(seqSnapshot.acceptedSignals(), parSnapshot.acceptedSignals()),
                () -> assertEquals(seqSnapshot.signalBudgetExhausted(), parSnapshot.signalBudgetExhausted()),
                () -> assertEquals(seqSnapshot.signalOccurrences(), parSnapshot.signalOccurrences()),
                () -> assertEquals(seqSnapshot.aeonResults(), parSnapshot.aeonResults()),
                () -> assertEquals(seqSnapshot.traceEntries(), parSnapshot.traceEntries()),
                () -> assertEquals(seqSnapshot, parSnapshot));
    }

    @Test
    void preservesAlreadyQueuedStepsAfterSignalBudgetExhaustion() {
        var seed = node(1);
        var root = node(2);
        var first = node(3);
        var second = node(4);
        root.connect(first);
        root.connect(second);
        var aeon = aeonWith(seed, root, first, second);
        var inputs = List.of(
                new AeonInput(seed.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));
        NodeProcessor processor = (node, input) -> node.getId().equals(root.getId())
                ? new NodeProcessingResult(List.of(input, input))
                : NodeProcessingResult.noOutput();

        assertContextualEquivalence(
                aeon,
                inputs,
                processor,
                PropagationConfig.routeAll(10, 1),
                new CognitiveBudget(10, 6, 30));
    }

    @Test
    void ignoresSpeculativeSignalBudgetExhaustionAfterStepBudgetTruncation() {
        var seed = node(1);
        var root = node(2);
        var child = node(3);
        root.connect(child);
        var aeon = aeonWith(seed, root, child);
        var inputs = List.of(
                new AeonInput(seed.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));
        NodeProcessor processor = (node, input) -> {
            if (node.getId().equals(root.getId())) {
                return new NodeProcessingResult(List.of(input));
            }
            if (node.getId().equals(child.getId())) {
                return new NodeProcessingResult(List.of(input, input));
            }
            return NodeProcessingResult.noOutput();
        };

        assertContextualEquivalence(
                aeon,
                inputs,
                processor,
                PropagationConfig.routeAll(2, 1),
                new CognitiveBudget(2, 4, 30));
    }

    @Test
    void traceBudgetExhaustionDoesNotChangeCognitionOrResults() {
        var root = node(1);
        var child = node(2);
        root.connect(child);
        var aeon = aeonWith(root, child);

        var inputs = List.of(
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));

        var budget = new CognitiveBudget(20, 20, 2);

        var seqContext = new CognitiveContext(budget);
        var seqResult = sequentialCoordinator.coordinate(
                aeon,
                inputs,
                (node, input) -> new NodeProcessingResult(List.of(input)),
                PropagationConfig.routeAll(10, 1),
                seqContext);
        var seqSnapshot = seqContext.complete(CognitiveCycleOutcome.SUCCESS);

        var parContext = new CognitiveContext(budget);
        var parResult = parallelCoordinator.coordinate(
                aeon,
                inputs,
                (node, input) -> new NodeProcessingResult(List.of(input)),
                PropagationConfig.routeAll(10, 1),
                parContext);
        var parSnapshot = parContext.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(seqResult, parResult),
                () -> assertEquals(seqSnapshot.traceEntries().size(), parSnapshot.traceEntries().size()),
                () -> assertEquals(seqSnapshot.omittedTraceEntries(), parSnapshot.omittedTraceEntries()),
                () -> assertEquals(seqSnapshot.traceBudgetExhausted(), parSnapshot.traceBudgetExhausted()),
                () -> assertEquals(seqSnapshot, parSnapshot));
    }

    @Test
    void processingFailureInContextProducesExactFailureTrace() {
        var root = node(1);
        var aeon = aeonWith(root);
        var failure = new IllegalStateException("processor failed");

        var inputs = List.of(
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));

        NodeProcessor failingProcessor = (node, input) -> {
            if (input.frequencyState().amplitude() == 2.0) {
                throw failure;
            }
            return new NodeProcessingResult(List.of(input));
        };

        var seqContext = new CognitiveContext(new CognitiveBudget(10, 10, 20));
        var seqException = assertThrows(
                IllegalStateException.class,
                () -> sequentialCoordinator.coordinate(aeon, inputs, failingProcessor, PropagationConfig.routeAll(2, 0), seqContext));
        var seqSnapshot = seqContext.complete(CognitiveCycleOutcome.FAILURE);

        var parContext = new CognitiveContext(new CognitiveBudget(10, 10, 20));
        var parException = assertThrows(
                IllegalStateException.class,
                () -> parallelCoordinator.coordinate(aeon, inputs, failingProcessor, PropagationConfig.routeAll(2, 0), parContext));
        var parSnapshot = parContext.complete(CognitiveCycleOutcome.FAILURE);

        assertAll(
                () -> assertSame(failure, seqException),
                () -> assertSame(failure, parException),
                () -> assertEquals(seqSnapshot.outcome(), parSnapshot.outcome()),
                () -> assertEquals(seqSnapshot.processedSteps(), parSnapshot.processedSteps()),
                () -> assertEquals(seqSnapshot.acceptedSignals(), parSnapshot.acceptedSignals()),
                () -> assertEquals(seqSnapshot.signalOccurrences(), parSnapshot.signalOccurrences()),
                () -> assertEquals(seqSnapshot.aeonResults(), parSnapshot.aeonResults()),
                () -> assertEquals(seqSnapshot.traceEntries(), parSnapshot.traceEntries()),
                () -> assertEquals(seqSnapshot, parSnapshot));
    }

    @Test
    void rejectsLegacyOnlyPropagationEngineInContextMode() {
        var calls = new AtomicInteger();
        SignalPropagationEngine legacyEngine = (start, input, processor, config) -> {
            calls.incrementAndGet();
            throw new AssertionError("legacy engine must not execute");
        };
        try (var legacyParallelCoordinator = new BoundedParallelAeonCoordinator(legacyEngine, 4, 1)) {
            var root = node(1);
            var aeon = aeonWith(root);

            var failure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> legacyParallelCoordinator.coordinate(
                            aeon,
                            List.of(new AeonInput(root.getId(), signal(1.0))),
                            (node, input) -> NodeProcessingResult.noOutput(),
                            PropagationConfig.routeAll(1, 0),
                            new CognitiveContext(new CognitiveBudget(1, 1, 1))));

            assertAll(
                    () -> assertTrue(failure.getMessage().contains("CognitiveSignalPropagationEngine")),
                    () -> assertEquals(0, calls.get()));
        }
    }

    @Test
    void multiWaveContextualCoordinationPreservesEquivalenceAndBypassesExhaustedWaves() {
        var root1 = node(1);
        var child1 = node(2);
        root1.connect(child1);

        var aeon = aeonWith(root1, child1);

        var inputs = new ArrayList<AeonInput>();
        for (int i = 0; i < 20; i++) {
            inputs.add(new AeonInput(root1.getId(), signal(i + 1.0)));
        }

        NodeProcessor processor = (node, input) -> new NodeProcessingResult(List.of(input));
        var config = PropagationConfig.routeAll(10, 2);

        // Budget allows only 6 steps (exhausts during input 2 in wave 1)
        var budget = new CognitiveBudget(6, 50, 100);

        try (var customParallelCoordinator = new BoundedParallelAeonCoordinator(engine, 2, 2)) {
            var seqContext = new CognitiveContext(budget);
            var seqResult = sequentialCoordinator.coordinate(aeon, inputs, processor, config, seqContext);
            var seqSnapshot = seqContext.complete(CognitiveCycleOutcome.SUCCESS);

            var parContext = new CognitiveContext(budget);
            var parResult = customParallelCoordinator.coordinate(aeon, inputs, processor, config, parContext);
            var parSnapshot = parContext.complete(CognitiveCycleOutcome.SUCCESS);

            assertAll(
                    () -> assertEquals(seqResult, parResult),
                    () -> assertEquals(20, parResult.inputResults().size()),
                    () -> assertEquals(seqSnapshot.processedSteps(), parSnapshot.processedSteps()),
                    () -> assertEquals(seqSnapshot.stepBudgetExhausted(), parSnapshot.stepBudgetExhausted()),
                    () -> assertTrue(parSnapshot.stepBudgetExhausted()),
                    () -> assertEquals(seqSnapshot.aeonResults(), parSnapshot.aeonResults()),
                    () -> assertEquals(seqSnapshot.traceEntries(), parSnapshot.traceEntries()),
                    () -> assertEquals(seqSnapshot, parSnapshot));
        }
    }

    @Test
    void suppressesFailureFromWorkBeyondTheAdmittedStepBudget() {
        var root = node(1);
        var aeon = aeonWith(root);
        var inputs = List.of(
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));
        var failure = new IllegalStateException("unadmitted failure");
        NodeProcessor processor = (node, input) -> {
            if (input.frequencyState().amplitude() == 2.0) {
                throw failure;
            }
            return NodeProcessingResult.noOutput();
        };

        var budget = new CognitiveBudget(1, 10, 20);
        var sequentialContext = new CognitiveContext(budget);
        var sequentialResult = sequentialCoordinator.coordinate(
                aeon, inputs, processor, PropagationConfig.routeAll(1, 0), sequentialContext);
        var sequentialSnapshot = sequentialContext.complete(CognitiveCycleOutcome.SUCCESS);

        var parallelContext = new CognitiveContext(budget);
        var parallelResult = parallelCoordinator.coordinate(
                aeon, inputs, processor, PropagationConfig.routeAll(1, 0), parallelContext);
        var parallelSnapshot = parallelContext.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(sequentialResult, parallelResult),
                () -> assertEquals(sequentialSnapshot, parallelSnapshot));
    }

    @Test
    void suppressesFailureFromWorkBeyondTheAdmittedSignalBudget() {
        var seed = node(1);
        var root = node(2);
        var child = node(3);
        root.connect(child);
        var aeon = aeonWith(seed, root, child);
        var inputs = List.of(
                new AeonInput(seed.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));
        var failure = new IllegalStateException("unadmitted signal-path failure");
        NodeProcessor processor = (node, input) -> {
            if (node.getId().equals(child.getId())) {
                throw failure;
            }
            return new NodeProcessingResult(List.of(input));
        };

        var budget = new CognitiveBudget(10, 4, 30);
        var sequentialContext = new CognitiveContext(budget);
        var sequentialResult = sequentialCoordinator.coordinate(
                aeon, inputs, processor, PropagationConfig.routeAll(2, 1), sequentialContext);
        var sequentialSnapshot = sequentialContext.complete(CognitiveCycleOutcome.SUCCESS);

        var parallelContext = new CognitiveContext(budget);
        var parallelResult = parallelCoordinator.coordinate(
                aeon, inputs, processor, PropagationConfig.routeAll(2, 1), parallelContext);
        var parallelSnapshot = parallelContext.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(sequentialResult, parallelResult),
                () -> assertEquals(sequentialSnapshot, parallelSnapshot));
    }

    @Test
    void preservesFullEmissionCountWhenSignalBudgetTruncatesWorkerLog() {
        var root = node(1);
        var aeon = aeonWith(root);
        var inputs = List.of(
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));
        NodeProcessor processor = (node, input) -> input.frequencyState().amplitude() == 1.0
                ? NodeProcessingResult.noOutput()
                : new NodeProcessingResult(List.of(input, input, input));

        assertContextualEquivalence(
                aeon,
                inputs,
                processor,
                PropagationConfig.routeAll(1, 0),
                new CognitiveBudget(2, 3, 20));
    }

    @Test
    void admitsAllFinalStepEmissionsAfterStepBudgetExhaustion() {
        var root = node(1);
        var child = node(2);
        root.connect(child);
        var aeon = aeonWith(root, child);
        var inputs = List.of(
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(child.getId(), signal(2.0)));
        NodeProcessor processor = (node, input) -> node.getId().equals(root.getId())
                ? new NodeProcessingResult(List.of(input, input))
                : NodeProcessingResult.noOutput();

        assertContextualEquivalence(
                aeon,
                inputs,
                processor,
                PropagationConfig.routeAll(2, 1),
                new CognitiveBudget(1, 6, 20));
    }

    @Test
    void derivesHopLimitOnlyFromAdmittedSteps() {
        var seed = node(1);
        var root = node(2);
        var child = node(3);
        var grandChild = node(4);
        root.connect(child);
        child.connect(grandChild);
        var aeon = aeonWith(seed, root, child, grandChild);
        var inputs = List.of(
                new AeonInput(seed.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));

        assertContextualEquivalence(
                aeon,
                inputs,
                (node, input) -> new NodeProcessingResult(List.of(input)),
                PropagationConfig.routeAll(2, 1),
                new CognitiveBudget(2, 10, 30));
    }

    private void assertContextualEquivalence(
            Aeon aeon,
            List<AeonInput> inputs,
            NodeProcessor processor,
            PropagationConfig config,
            CognitiveBudget budget) {
        var sequentialContext = new CognitiveContext(budget);
        var sequentialResult = sequentialCoordinator.coordinate(aeon, inputs, processor, config, sequentialContext);
        var sequentialSnapshot = sequentialContext.complete(CognitiveCycleOutcome.SUCCESS);

        var parallelContext = new CognitiveContext(budget);
        var parallelResult = parallelCoordinator.coordinate(aeon, inputs, processor, config, parallelContext);
        var parallelSnapshot = parallelContext.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(sequentialResult, parallelResult),
                () -> assertEquals(sequentialSnapshot, parallelSnapshot));
    }

    private Aeon aeonWith(Node... nodes) {
        var aeon = new Aeon(uuid(100), AeonPurpose.REASONING);
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
}
