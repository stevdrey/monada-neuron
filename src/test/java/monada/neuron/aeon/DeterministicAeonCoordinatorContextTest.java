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

class DeterministicAeonCoordinatorContextTest {

    private final DeterministicAeonCoordinator coordinator = new DeterministicAeonCoordinator(
            new DeterministicSignalPropagationEngine());

    @Test
    void stepBudgetIsGlobalAcrossInputsAndSkippedInputsRemainOrdered() {
        var root = node(1);
        var child = node(2);
        root.connect(child);
        var aeon = aeonWith(root, child);
        var inputs = List.of(
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));
        var context = new CognitiveContext(new CognitiveBudget(2, 20, 30));

        var result = coordinator.coordinate(
                aeon,
                inputs,
                (node, input) -> new NodeProcessingResult(List.of(input)),
                PropagationConfig.routeAll(10, 1),
                context);
        var snapshot = context.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(2, result.inputResults().size()),
                () -> assertEquals(2,
                        result.inputResults().get(0).propagationResult().processedSteps()),
                () -> assertFalse(result.inputResults().get(0).propagationResult().stepLimitReached()),
                () -> assertEquals(0,
                        result.inputResults().get(1).propagationResult().processedSteps()),
                () -> assertTrue(result.inputResults().get(1).propagationResult().stepLimitReached()),
                () -> assertEquals(2, snapshot.processedSteps()),
                () -> assertTrue(snapshot.stepBudgetExhausted()),
                () -> assertEquals(1, snapshot.aeonResults().size()),
                () -> assertEquals(inputs.getFirst(), snapshot.aeonResults().getFirst().inputResult().input()),
                () -> assertEquals(2, snapshot.traceEntries().stream()
                        .filter(entry -> entry.event() instanceof CognitiveTraceEvent.AeonInputCompleted)
                        .count()));
    }

    @Test
    void signalBudgetCountsInputsEmissionsAndAcceptedDeliveries() {
        var root = node(1);
        var first = node(2);
        var second = node(3);
        root.connect(second);
        root.connect(first);
        var aeon = aeonWith(root, first, second);
        var processedNodes = new ArrayList<UUID>();
        var context = new CognitiveContext(new CognitiveBudget(10, 5, 40));

        var result = coordinator.coordinate(
                aeon,
                List.of(new AeonInput(root.getId(), signal(1.0))),
                (node, input) -> {
                    processedNodes.add(node.getId());
                    return node.getId().equals(root.getId())
                            ? new NodeProcessingResult(List.of(input, input))
                            : NodeProcessingResult.noOutput();
                },
                PropagationConfig.routeAll(10, 1),
                context);
        var snapshot = context.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(List.of(root.getId(), first.getId(), second.getId()), processedNodes),
                () -> assertEquals(3,
                        result.inputResults().getFirst().propagationResult().processedSteps()),
                () -> assertEquals(List.of(signal(1.0), signal(1.0)),
                        result.inputResults().getFirst().propagationResult().emittedSignals()),
                () -> assertEquals(5, snapshot.acceptedSignals()),
                () -> assertTrue(snapshot.signalBudgetExhausted()),
                () -> assertEquals(1,
                        snapshot.signalOccurrences().stream()
                                .filter(CognitiveSignalOccurrence.Input.class::isInstance)
                                .count()),
                () -> assertEquals(2,
                        snapshot.signalOccurrences().stream()
                                .filter(CognitiveSignalOccurrence.Emitted.class::isInstance)
                                .count()),
                () -> assertEquals(2,
                        snapshot.signalOccurrences().stream()
                                .filter(CognitiveSignalOccurrence.Delivered.class::isInstance)
                                .count()),
                () -> assertEquals(2, snapshot.traceEntries().stream()
                        .filter(entry -> entry.event() instanceof CognitiveTraceEvent.SignalRouted)
                        .count()));
    }

    @Test
    void traceKeepsInputMetadataWithoutRetainingSignalBudgetRejections() {
        var root = node(1);
        var aeon = aeonWith(root);
        var firstSignal = signal(1.0);
        var secondSignal = signal(2.0);
        var thirdSignal = signal(3.0);
        var inputs = List.of(
                new AeonInput(root.getId(), firstSignal),
                new AeonInput(root.getId(), secondSignal),
                new AeonInput(root.getId(), thirdSignal));
        var context = new CognitiveContext(new CognitiveBudget(3, 1, 20));

        var result = coordinator.coordinate(
                aeon,
                inputs,
                (node, input) -> NodeProcessingResult.noOutput(),
                PropagationConfig.routeAll(3, 0),
                context);
        var snapshot = context.complete(CognitiveCycleOutcome.SUCCESS);
        var startedInputs = snapshot.traceEntries().stream()
                .map(entry -> entry.event())
                .filter(CognitiveTraceEvent.AeonInputStarted.class::isInstance)
                .map(CognitiveTraceEvent.AeonInputStarted.class::cast)
                .toList();

        assertAll(
                () -> assertEquals(List.of(1, 0, 0), result.inputResults().stream()
                        .map(inputResult -> inputResult.propagationResult().processedSteps())
                        .toList()),
                () -> assertEquals(1, snapshot.acceptedSignals()),
                () -> assertEquals(List.of(new CognitiveSignalOccurrence.Input(
                                0L, root.getId(), firstSignal)),
                        snapshot.signalOccurrences()),
                () -> assertEquals(List.of(
                                new CognitiveTraceEvent.AeonInputStarted(aeon.getId(), root.getId()),
                                new CognitiveTraceEvent.AeonInputStarted(aeon.getId(), root.getId()),
                                new CognitiveTraceEvent.AeonInputStarted(aeon.getId(), root.getId())),
                        startedInputs),
                () -> assertTrue(snapshot.signalBudgetExhausted()));
    }

    @Test
    void deterministicReplayIsStableAndTraceCapacityDoesNotChangeCognition() {
        var root = node(1);
        var second = node(2);
        var third = node(3);
        root.connect(third);
        root.connect(second);
        var aeon = aeonWith(root, second, third);
        var input = List.of(new AeonInput(root.getId(), signal(1.0)));
        NodeProcessor processor = (node, received) -> node.getId().equals(root.getId())
                ? new NodeProcessingResult(List.of(received))
                : NodeProcessingResult.noOutput();

        var firstContext = new CognitiveContext(new CognitiveBudget(10, 10, 30));
        var firstResult = coordinator.coordinate(
                aeon, input, processor, PropagationConfig.routeAll(10, 1), firstContext);
        var firstSnapshot = firstContext.complete(CognitiveCycleOutcome.SUCCESS);

        var secondContext = new CognitiveContext(new CognitiveBudget(10, 10, 30));
        var secondResult = coordinator.coordinate(
                aeon, input, processor, PropagationConfig.routeAll(10, 1), secondContext);
        var secondSnapshot = secondContext.complete(CognitiveCycleOutcome.SUCCESS);

        var limitedTraceContext = new CognitiveContext(new CognitiveBudget(10, 10, 1));
        var limitedTraceResult = coordinator.coordinate(
                aeon, input, processor, PropagationConfig.routeAll(10, 1), limitedTraceContext);
        var limitedTraceSnapshot = limitedTraceContext.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(firstResult, secondResult),
                () -> assertEquals(firstSnapshot, secondSnapshot),
                () -> assertEquals(firstResult, limitedTraceResult),
                () -> assertEquals(firstSnapshot.signalOccurrences(), limitedTraceSnapshot.signalOccurrences()),
                () -> assertEquals(firstSnapshot.aeonResults(), limitedTraceSnapshot.aeonResults()),
                () -> assertEquals(1, limitedTraceSnapshot.traceEntries().size()),
                () -> assertTrue(limitedTraceSnapshot.traceBudgetExhausted()),
                () -> assertTrue(limitedTraceSnapshot.omittedTraceEntries() > 0));
    }

    @Test
    void processingFailuresRemainVisibleAndTheOwnerCompletesTheFailureSnapshot() {
        var root = node(1);
        var aeon = aeonWith(root);
        var context = new CognitiveContext(new CognitiveBudget(2, 4, 10));
        var failure = new IllegalStateException("processor failed");

        var actual = assertThrows(
                IllegalStateException.class,
                () -> coordinator.coordinate(
                        aeon,
                        List.of(new AeonInput(root.getId(), signal(1.0))),
                        (node, input) -> {
                            throw failure;
                        },
                        PropagationConfig.routeAll(2, 0),
                        context));
        var snapshot = context.complete(CognitiveCycleOutcome.FAILURE);

        assertAll(
                () -> assertSame(failure, actual),
                () -> assertEquals(CognitiveCycleOutcome.FAILURE, snapshot.outcome()),
                () -> assertEquals(0, snapshot.processedSteps()),
                () -> assertEquals(0, snapshot.aeonResults().size()),
                () -> assertEquals(1, snapshot.traceEntries().size()),
                () -> assertInstanceOf(CognitiveTraceEvent.AeonInputStarted.class,
                        snapshot.traceEntries().getFirst().event()));
    }

    @Test
    void rejectsLegacyOnlyPropagationEngineBeforeItExecutes() {
        var calls = new AtomicInteger();
        SignalPropagationEngine legacyEngine = (start, input, processor, config) -> {
            calls.incrementAndGet();
            throw new AssertionError("legacy engine must not execute");
        };
        var legacyCoordinator = new DeterministicAeonCoordinator(legacyEngine);
        var root = node(1);
        var aeon = aeonWith(root);

        var failure = assertThrows(
                UnsupportedOperationException.class,
                () -> legacyCoordinator.coordinate(
                        aeon,
                        List.of(new AeonInput(root.getId(), signal(1.0))),
                        (node, input) -> NodeProcessingResult.noOutput(),
                        PropagationConfig.routeAll(1, 0),
                        new CognitiveContext(new CognitiveBudget(1, 1, 1))));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("CognitiveSignalPropagationEngine")),
                () -> assertEquals(0, calls.get()));
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
