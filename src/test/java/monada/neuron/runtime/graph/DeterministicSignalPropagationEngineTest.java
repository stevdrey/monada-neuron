package monada.neuron.runtime.graph;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicSignalPropagationEngineTest {

    private final UUID rootId = uuid(1);
    private final UUID lowId = uuid(2);
    private final UUID highId = uuid(3);
    private final UUID joinId = uuid(4);

    private final SignalPropagationEngine engine = new DeterministicSignalPropagationEngine();

    @Test
    void propagatesBreadthFirstThroughAChainWithoutMutatingNodes() {
        var firstState = new FrequencyState(1.0, 10.0, 0.0);
        var secondState = new FrequencyState(2.0, 20.0, 0.0);
        var thirdState = new FrequencyState(3.0, 30.0, 0.0);
        var first = node(rootId, firstState);
        var second = node(lowId, secondState);
        var third = node(highId, thirdState);
        first.connect(second);
        second.connect(third);
        var outputs = Map.of(rootId, signal(1.0), lowId, signal(2.0), highId, signal(3.0));

        var result = engine.propagate(
                first,
                signal(0.5),
                (node, input) -> new NodeProcessingResult(List.of(outputs.get(node.getId()))),
                PropagationConfig.routeAll(10, 10));

        assertAll(
                () -> assertEquals(List.of(signal(1.0), signal(2.0), signal(3.0)),
                        result.emittedSignals()),
                () -> assertEquals(3, result.processedSteps()),
                () -> assertFalse(result.stepLimitReached()),
                () -> assertFalse(result.hopLimitReached()),
                () -> assertSame(firstState, first.getFrequencyState()),
                () -> assertSame(secondState, second.getFrequencyState()),
                () -> assertSame(thirdState, third.getFrequencyState()),
                () -> assertTrue(first.getHistory().isEmpty()),
                () -> assertTrue(second.getHistory().isEmpty()),
                () -> assertTrue(third.getHistory().isEmpty()));
    }

    @Test
    void preservesEmissionThenUuidOrderIncludingDuplicateSignals() {
        var firstGraph = branchGraph(true);
        var secondGraph = branchGraph(false);
        NodeProcessor processor = (node, input) -> {
            if (node.getId().equals(rootId)) {
                return new NodeProcessingResult(List.of(signal(1.0), signal(2.0), signal(1.0)));
            }
            double offset = node.getId().equals(lowId) ? 10.0 : 20.0;
            return new NodeProcessingResult(List.of(
                    signal(offset + input.frequencyState().amplitude())));
        };

        var first = engine.propagate(
                firstGraph.root(), signal(0.5), processor, PropagationConfig.routeAll(20, 1));
        var second = engine.propagate(
                secondGraph.root(), signal(0.5), processor, PropagationConfig.routeAll(20, 1));

        var expected = List.of(
                signal(1.0), signal(2.0), signal(1.0),
                signal(11.0), signal(21.0),
                signal(12.0), signal(22.0),
                signal(11.0), signal(21.0));
        assertAll(
                () -> assertEquals(expected, first.emittedSignals()),
                () -> assertEquals(7, first.processedSteps()),
                () -> assertEquals(first, second));
    }

    @Test
    void suppressesDuplicateConnectionsAndDoesNotReachDisconnectedNodes() {
        var root = node(rootId);
        var target = node(lowId);
        var disconnected = node(highId);
        assertTrue(root.connect(target));
        assertFalse(root.connect(target));
        var outputs = Map.of(rootId, signal(1.0), lowId, signal(2.0), highId, signal(3.0));

        var result = engine.propagate(
                root,
                signal(0.5),
                (node, input) -> new NodeProcessingResult(List.of(outputs.get(node.getId()))),
                PropagationConfig.routeAll(10, 10));

        assertAll(
                () -> assertEquals(List.of(signal(1.0), signal(2.0)), result.emittedSignals()),
                () -> assertEquals(2, result.processedSteps()),
                () -> assertEquals(FrequencyState.ZERO, disconnected.getFrequencyState()));
    }

    @Test
    void noOutputStopsTheCurrentBranch() {
        var root = node(rootId);
        var target = node(lowId);
        root.connect(target);

        var result = engine.propagate(
                root,
                signal(1.0),
                (node, input) -> node.getId().equals(rootId)
                        ? NodeProcessingResult.noOutput()
                        : new NodeProcessingResult(List.of(signal(2.0))),
                PropagationConfig.routeAll(10, 10));

        assertAll(
                () -> assertTrue(result.emittedSignals().isEmpty()),
                () -> assertEquals(1, result.processedSteps()),
                () -> assertFalse(result.stepLimitReached()),
                () -> assertFalse(result.hopLimitReached()));
    }

    @Test
    void resonancePolicyRoutesOnlyAcceptedTargetsThroughTheEngine() {
        var root = node(rootId);
        var resonant = node(lowId, new FrequencyState(1.0, 10.0, 0.0));
        var opposed = node(highId, new FrequencyState(1.0, 10.0, StrictMath.PI));
        root.connect(opposed);
        root.connect(resonant);
        var policy = new ResonanceThresholdRoutingPolicy(new ScalarResonanceMetric(), 1.0);

        var result = engine.propagate(
                root,
                signal(0.5),
                (node, input) -> node.getId().equals(rootId)
                        ? new NodeProcessingResult(List.of(signal(1.0)))
                        : new NodeProcessingResult(List.of(signal(2.0))),
                new PropagationConfig(10, 1, policy));

        assertAll(
                () -> assertEquals(List.of(signal(1.0), signal(2.0)),
                        result.emittedSignals()),
                () -> assertEquals(2, result.processedSteps()),
                () -> assertFalse(result.stepLimitReached()),
                () -> assertFalse(result.hopLimitReached()));
    }

    @Test
    void rejectedRoutesAtMaximumHopDoNotReportHopTruncation() {
        var root = node(rootId);
        root.connect(node(lowId));

        var result = engine.propagate(
                root,
                signal(1.0),
                (node, input) -> new NodeProcessingResult(List.of(input)),
                new PropagationConfig(1, 0, (source, target, signal) -> false));

        assertAll(
                () -> assertEquals(1, result.processedSteps()),
                () -> assertFalse(result.stepLimitReached()),
                () -> assertFalse(result.hopLimitReached()));
    }

    @Test
    void processesEverySignalArrivalAtAFanInNode() {
        var root = node(rootId);
        var left = node(lowId);
        var right = node(highId);
        var join = node(joinId);
        root.connect(right);
        root.connect(left);
        left.connect(join);
        right.connect(join);

        NodeProcessor processor = (node, input) -> {
            if (node.getId().equals(rootId)) {
                return new NodeProcessingResult(List.of(signal(1.0)));
            }
            if (node.getId().equals(lowId)) {
                return new NodeProcessingResult(List.of(signal(2.0)));
            }
            if (node.getId().equals(highId)) {
                return new NodeProcessingResult(List.of(signal(3.0)));
            }
            return new NodeProcessingResult(List.of(
                    signal(10.0 + input.frequencyState().amplitude())));
        };

        var result = engine.propagate(
                root, signal(0.5), processor, PropagationConfig.routeAll(10, 10));

        assertAll(
                () -> assertEquals(
                        List.of(signal(1.0), signal(2.0), signal(3.0),
                                signal(12.0), signal(13.0)),
                        result.emittedSignals()),
                () -> assertEquals(5, result.processedSteps()),
                () -> assertFalse(result.stepLimitReached()),
                () -> assertFalse(result.hopLimitReached()));
    }

    @Test
    void hopLimitTerminatesCyclesAfterProcessingTheMaximumHop() {
        var first = node(rootId);
        var second = node(lowId);
        first.connect(second);
        second.connect(first);
        var input = signal(1.0);

        var result = engine.propagate(
                first,
                input,
                (node, received) -> new NodeProcessingResult(List.of(received)),
                PropagationConfig.routeAll(10, 2));

        assertAll(
                () -> assertEquals(List.of(input, input, input), result.emittedSignals()),
                () -> assertEquals(3, result.processedSteps()),
                () -> assertFalse(result.stepLimitReached()),
                () -> assertTrue(result.hopLimitReached()));
    }

    @Test
    void stepLimitTerminatesCyclesWhenWorkRemains() {
        var first = node(rootId);
        var second = node(lowId);
        first.connect(second);
        second.connect(first);
        var input = signal(1.0);

        var result = engine.propagate(
                first,
                input,
                (node, received) -> new NodeProcessingResult(List.of(received)),
                PropagationConfig.routeAll(2, 10));

        assertAll(
                () -> assertEquals(List.of(input, input), result.emittedSignals()),
                () -> assertEquals(2, result.processedSteps()),
                () -> assertTrue(result.stepLimitReached()),
                () -> assertFalse(result.hopLimitReached()));
    }

    @Test
    void reportsBothLimitsIndependently() {
        var first = node(rootId);
        var second = node(lowId);
        first.connect(second);
        second.connect(first);
        var input = signal(1.0);
        NodeProcessor processor = (node, received) -> node.getId().equals(rootId)
                ? new NodeProcessingResult(List.of(received, received))
                : new NodeProcessingResult(List.of(received));

        var result = engine.propagate(
                first, input, processor, PropagationConfig.routeAll(2, 1));

        assertAll(
                () -> assertEquals(List.of(input, input, input), result.emittedSignals()),
                () -> assertEquals(2, result.processedSteps()),
                () -> assertTrue(result.stepLimitReached()),
                () -> assertTrue(result.hopLimitReached()));
    }

    @Test
    void finishingExactlyAtTheStepLimitIsNotTruncation() {
        var first = node(rootId);
        var second = node(lowId);
        first.connect(second);

        var result = engine.propagate(
                first,
                signal(1.0),
                (node, input) -> node.getId().equals(rootId)
                        ? new NodeProcessingResult(List.of(input))
                        : NodeProcessingResult.noOutput(),
                PropagationConfig.routeAll(2, 10));

        assertAll(
                () -> assertEquals(2, result.processedSteps()),
                () -> assertFalse(result.stepLimitReached()),
                () -> assertFalse(result.hopLimitReached()));
    }

    @Test
    void globalContextStepBudgetDoesNotSetThePropagationStepLimit() {
        var root = node(rootId);
        root.connect(node(lowId));
        var context = new CognitiveContext(new CognitiveBudget(1, 3, 0));
        var contextualEngine = new DeterministicSignalPropagationEngine();
        NodeProcessor passthrough = (node, input) -> new NodeProcessingResult(List.of(input));
        var config = PropagationConfig.routeAll(2, 1);

        var truncated = contextualEngine.propagate(
                root, signal(1.0), passthrough, config, context);
        var skipped = contextualEngine.propagate(
                root, signal(2.0), passthrough, config, context);

        assertAll(
                () -> assertEquals(1, truncated.processedSteps()),
                () -> assertFalse(truncated.stepLimitReached()),
                () -> assertEquals(0, skipped.processedSteps()),
                () -> assertFalse(skipped.stepLimitReached()),
                () -> assertTrue(context.stepBudgetExhausted()));
    }

    @Test
    void propagationAndRoutingFailuresReachTheCaller() {
        var root = node(rootId);
        var target = node(lowId);
        root.connect(target);
        var processingFailure = new IllegalStateException("processing failed");
        var routingFailure = new IllegalArgumentException("routing failed");

        var actualProcessingFailure = assertThrows(
                IllegalStateException.class,
                () -> engine.propagate(
                        root,
                        signal(1.0),
                        (node, input) -> {
                            throw processingFailure;
                        },
                        PropagationConfig.routeAll(10, 10)));
        var actualRoutingFailure = assertThrows(
                IllegalArgumentException.class,
                () -> engine.propagate(
                        root,
                        signal(1.0),
                        (node, input) -> new NodeProcessingResult(List.of(input)),
                        new PropagationConfig(10, 10, (source, destination, signal) -> {
                            throw routingFailure;
                        })));

        assertAll(
                () -> assertSame(processingFailure, actualProcessingFailure),
                () -> assertSame(routingFailure, actualRoutingFailure),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> engine.propagate(
                                root,
                                signal(1.0),
                                (node, input) -> null,
                                PropagationConfig.routeAll(10, 10))));
    }

    @Test
    void rejectsNullPropagationArguments() {
        var node = node(rootId);
        var input = signal(1.0);
        NodeProcessor processor = (view, received) -> NodeProcessingResult.noOutput();
        var config = PropagationConfig.routeAll(1, 0);

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> engine.propagate(null, input, processor, config)),
                () -> assertThrows(NullPointerException.class,
                        () -> engine.propagate(node, null, processor, config)),
                () -> assertThrows(NullPointerException.class,
                        () -> engine.propagate(node, input, null, config)),
                () -> assertThrows(NullPointerException.class,
                        () -> engine.propagate(node, input, processor, null)));
    }

    private BranchGraph branchGraph(boolean connectHighFirst) {
        var root = node(rootId);
        var low = node(lowId);
        var high = node(highId);
        if (connectHighFirst) {
            root.connect(high);
            root.connect(low);
        } else {
            root.connect(low);
            root.connect(high);
        }
        return new BranchGraph(root);
    }

    private Node node(UUID id) {
        return node(id, FrequencyState.ZERO);
    }

    private Node node(UUID id, FrequencyState state) {
        return new Node.Builder()
                .id(id)
                .type(NodeType.PROCESSOR)
                .frequencyState(state)
                .build();
    }

    private Signal signal(double amplitude) {
        return new Signal(
                SignalKind.INTERMEDIATE,
                new FrequencyState(amplitude, 10.0, 0.0));
    }

    private UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private record BranchGraph(Node root) {
    }
}
