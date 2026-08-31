package monada.neuron.aeon;

import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.runtime.graph.CompactGraphSnapshot;
import monada.neuron.runtime.graph.CompactSignalPropagationEngine;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.PropagationResult;
import monada.neuron.runtime.graph.SignalPropagationEngine;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedParallelAeonCoordinatorTest {

    private final SignalPropagationEngine graphEngine = new DeterministicSignalPropagationEngine();
    private final DeterministicAeonCoordinator sequentialCoordinator = new DeterministicAeonCoordinator(graphEngine);
    private final BoundedParallelAeonCoordinator parallelCoordinator = new BoundedParallelAeonCoordinator(graphEngine, 4, 2);

    @Test
    void emptyInputSucceedsWithoutCallingTheEngine() {
        var calls = new AtomicInteger();
        SignalPropagationEngine unusedEngine = (start, input, processor, config) -> {
            calls.incrementAndGet();
            throw new AssertionError("engine must not be called");
        };
        try (var emptyCoordinator = new BoundedParallelAeonCoordinator(unusedEngine)) {
            var result = emptyCoordinator.coordinate(
                    new Aeon(uuid(100), AeonPurpose.PERCEPTION),
                    List.of(),
                    (node, input) -> NodeProcessingResult.noOutput(),
                    PropagationConfig.routeAll(1, 0));

            assertAll(
                    () -> assertSame(AeonCoordinationResult.empty(), result),
                    () -> assertEquals(0, calls.get()));
        }
    }

    @Test
    void validatesEveryStartMembershipBeforeProcessing() {
        var aeon = aeonWith(node(uuid(1)));
        var calls = new AtomicInteger();
        SignalPropagationEngine countingEngine = (start, input, processor, config) -> {
            calls.incrementAndGet();
            return new PropagationResult(List.of(), 0, false, false);
        };
        try (var validatingCoordinator = new BoundedParallelAeonCoordinator(countingEngine, 4, 1)) {
            var inputs = List.of(
                    new AeonInput(uuid(1), signal(1.0)),
                    new AeonInput(uuid(99), signal(2.0)));

            var failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> validatingCoordinator.coordinate(
                            aeon,
                            inputs,
                            (node, input) -> NodeProcessingResult.noOutput(),
                            PropagationConfig.routeAll(1, 0)));

            assertAll(
                    () -> assertTrue(failure.getMessage().contains(uuid(99).toString())),
                    () -> assertEquals(0, calls.get()));
        }
    }

    @Test
    void preservesExactInputOrderAndEquivalentResultsToSequentialOracle() {
        var root1 = node(uuid(1));
        var child1 = node(uuid(2));
        root1.connect(child1);

        var root2 = node(uuid(3));
        var child2 = node(uuid(4));
        root2.connect(child2);

        var aeon = aeonWith(root1, child1, root2, child2);

        var inputs = new ArrayList<AeonInput>();
        for (int i = 0; i < 128; i++) {
            UUID startId = (i % 2 == 0) ? root1.getId() : root2.getId();
            inputs.add(new AeonInput(startId, signal(i * 1.5)));
        }

        NodeProcessor processor = (node, input) -> new NodeProcessingResult(List.of(input));
        var config = PropagationConfig.routeAll(10, 2);

        var sequentialResult = sequentialCoordinator.coordinate(aeon, inputs, processor, config);
        var parallelResult = parallelCoordinator.coordinate(aeon, inputs, processor, config);

        assertAll(
                () -> assertEquals(sequentialResult.inputResults().size(), parallelResult.inputResults().size()),
                () -> assertEquals(sequentialResult.inputResults(), parallelResult.inputResults()),
                () -> assertEquals(sequentialResult, parallelResult));
    }

    @Test
    void compatibleWithCompactSignalPropagationEngineBackend() {
        var root = node(uuid(1));
        var child = node(uuid(2));
        var target = node(uuid(3));
        root.connect(child);
        child.connect(target);

        var allNodes = List.of(root, child, target);
        var snapshot = CompactGraphSnapshot.compile(allNodes);
        var compactEngine = new CompactSignalPropagationEngine(snapshot);
        var aeon = aeonWith(root, child, target);

        var inputs = new ArrayList<AeonInput>();
        for (int i = 0; i < 32; i++) {
            inputs.add(new AeonInput(root.getId(), signal(i + 1.0)));
        }

        NodeProcessor processor = (node, input) -> new NodeProcessingResult(List.of(input));
        var config = PropagationConfig.routeAll(20, 3);

        try (var compactParallelCoordinator = new BoundedParallelAeonCoordinator(compactEngine, 4, 2)) {
            var sequentialReference = sequentialCoordinator.coordinate(aeon, inputs, processor, config);
            var parallelCompactResult = compactParallelCoordinator.coordinate(aeon, inputs, processor, config);

            assertEquals(sequentialReference, parallelCompactResult);
        }
    }

    @Test
    void fallsBackToSequentialWhenBelowThresholdOrSingleWorker() {
        var calls = new AtomicInteger();

        SignalPropagationEngine probeEngine = (startNode, input, processor, config) -> {
            calls.incrementAndGet();
            return new PropagationResult(List.of(input), 1, false, false);
        };

        var root = node(uuid(1));
        var aeon = aeonWith(root);
        var inputs = List.of(new AeonInput(root.getId(), signal(1.0)));

        try (var singleWorkerCoordinator = new BoundedParallelAeonCoordinator(probeEngine, 1, 1);
             var highThresholdCoordinator = new BoundedParallelAeonCoordinator(probeEngine, 4, 10)) {

            var res1 = singleWorkerCoordinator.coordinate(aeon, inputs, (n, s) -> NodeProcessingResult.noOutput(), PropagationConfig.routeAll(1, 0));
            var res2 = highThresholdCoordinator.coordinate(aeon, inputs, (n, s) -> NodeProcessingResult.noOutput(), PropagationConfig.routeAll(1, 0));

            assertAll(
                    () -> assertEquals(1, res1.inputResults().size()),
                    () -> assertEquals(1, res2.inputResults().size()),
                    () -> assertEquals(2, calls.get()));
        }
    }

    @Test
    void fallsBackToSequentialWhenEligibilityPolicyDeclaresWorkloadSequentialOnly() {
        var root = node(uuid(1));
        var aeon = aeonWith(root);
        var inputs = List.of(
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)),
                new AeonInput(root.getId(), signal(3.0)));

        try (var forcedSequentialCoordinator = new BoundedParallelAeonCoordinator(
                graphEngine,
                ForkJoinPool.commonPool(),
                4,
                2,
                AeonParallelEligibility.SEQUENTIAL_ONLY)) {

            var result = forcedSequentialCoordinator.coordinate(
                    aeon,
                    inputs,
                    (node, input) -> new NodeProcessingResult(List.of(input)),
                    PropagationConfig.routeAll(2, 0));

            var referenceResult = sequentialCoordinator.coordinate(
                    aeon,
                    inputs,
                    (node, input) -> new NodeProcessingResult(List.of(input)),
                    PropagationConfig.routeAll(2, 0));

            assertEquals(referenceResult, result);
        }
    }

    @Test
    void multiFailurePrioritizesLowestIndexExceptionAndSuppressesOthers() {
        var root = node(uuid(1));
        var aeon = aeonWith(root);

        var firstFailure = new IllegalStateException("input 1 failed");
        var secondFailure = new IllegalArgumentException("input 3 failed");

        var inputs = List.of(
                new AeonInput(root.getId(), signal(0.0)),
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)),
                new AeonInput(root.getId(), signal(3.0)));

        NodeProcessor processor = (node, input) -> {
            if (input.frequencyState().amplitude() == 1.0) {
                throw firstFailure;
            }
            if (input.frequencyState().amplitude() == 3.0) {
                throw secondFailure;
            }
            return NodeProcessingResult.noOutput();
        };

        var actual = assertThrows(
                IllegalStateException.class,
                () -> parallelCoordinator.coordinate(aeon, inputs, processor, PropagationConfig.routeAll(2, 0)));

        assertSame(firstFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(secondFailure, actual.getSuppressed()[0]);
    }

    @Test
    void validatesConstructorArguments() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new BoundedParallelAeonCoordinator(null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoundedParallelAeonCoordinator(graphEngine, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoundedParallelAeonCoordinator(graphEngine, 4, 0)),
                () -> assertThrows(NullPointerException.class,
                        () -> new BoundedParallelAeonCoordinator(graphEngine, null, 4, 2, AeonParallelEligibility.INDEPENDENT_READ_ONLY)),
                () -> assertThrows(NullPointerException.class,
                        () -> new BoundedParallelAeonCoordinator(graphEngine, ForkJoinPool.commonPool(), 4, 2, null)));
    }

    private Aeon aeonWith(Node... nodes) {
        var aeon = new Aeon(uuid(100), AeonPurpose.REASONING);
        for (var node : nodes) {
            aeon.addMember(node);
        }
        return aeon;
    }

    private Node node(UUID id) {
        return new Node.Builder().id(id).type(NodeType.PROCESSOR).build();
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
