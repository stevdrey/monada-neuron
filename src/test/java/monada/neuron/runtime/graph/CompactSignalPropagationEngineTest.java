package monada.neuron.runtime.graph;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.model.NodeView;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactSignalPropagationEngineTest {

    private final DeterministicSignalPropagationEngine reference =
            new DeterministicSignalPropagationEngine();

    @Test
    void compilesCanonicalNodesAndPreservesUuidOrderedTraversal() {
        var root = node(3L);
        var low = node(1L);
        var high = node(5L);
        root.connect(high);
        root.connect(low);
        low.connect(high);

        var snapshot = CompactGraphSnapshot.compile(List.of(high, root, low));
        var compact = new CompactSignalPropagationEngine(snapshot);

        assertAll(
                () -> assertEquals(3, snapshot.nodeCount()),
                () -> assertEquals(3, snapshot.edgeCount()),
                () -> assertTrue(snapshot.isCurrent()));

        assertEquivalent(
                compact,
                root,
                signal(1.0),
                (source, input) -> new NodeProcessingResult(List.of(input)),
                PropagationConfig.routeAll(10, 1));
    }

    @Test
    void preservesDuplicateFanInCycleRoutingAndLimitSemantics() {
        var root = node(0L);
        var low = node(1L);
        var high = node(2L);
        var join = node(3L);
        root.connect(high);
        root.connect(low);
        low.connect(join);
        high.connect(join);
        join.connect(root);

        var compact = new CompactSignalPropagationEngine(
                CompactGraphSnapshot.compile(List.of(join, high, root, low)));
        OutputStrategy duplicateFanIn = (source, input) -> {
            long id = source.getId().getLeastSignificantBits();
            if (id == 0L) {
                return new NodeProcessingResult(List.of(signal(1.0), signal(1.0)));
            }
            if (id == 1L) {
                return new NodeProcessingResult(List.of(signal(2.0)));
            }
            if (id == 2L) {
                return new NodeProcessingResult(List.of(signal(3.0)));
            }
            return new NodeProcessingResult(List.of(
                    signal(10.0 + input.frequencyState().amplitude())));
        };

        assertEquivalent(compact, root, signal(0.5), duplicateFanIn, PropagationConfig.routeAll(12, 3));
        assertEquivalent(compact, root, signal(0.5), duplicateFanIn, PropagationConfig.routeAll(2, 1));
        assertEquivalent(
                compact,
                root,
                signal(0.5),
                (source, input) -> NodeProcessingResult.noOutput(),
                PropagationConfig.routeAll(10, 10));
    }

    @Test
    void preservesThresholdRoutingAndRejectedHopLimitBehavior() {
        var root = node(0L);
        var resonant = node(1L, new FrequencyState(1.0, 10.0, 0.0));
        var opposed = node(2L, new FrequencyState(1.0, 10.0, StrictMath.PI));
        root.connect(opposed);
        root.connect(resonant);
        var compact = new CompactSignalPropagationEngine(
                CompactGraphSnapshot.compile(List.of(opposed, root, resonant)));
        var policy = new ResonanceThresholdRoutingPolicy(new ScalarResonanceMetric(), 1.0);

        assertEquivalent(
                compact,
                root,
                signal(0.5),
                (source, input) -> new NodeProcessingResult(List.of(signal(1.0))),
                new PropagationConfig(10, 1, policy));
        assertEquivalent(
                compact,
                root,
                signal(0.5),
                (source, input) -> new NodeProcessingResult(List.of(input)),
                new PropagationConfig(1, 0, (source, target, emitted) -> false));
    }

    @Test
    void detectsInvalidationOnlyForSuccessfulTopologyMutationsAndAllowsRecompilation() {
        var root = node(0L);
        var target = node(1L);
        var disconnected = node(2L);
        root.connect(target);
        long compiledVersion = root.getTopologyVersion();
        var snapshot = CompactGraphSnapshot.compile(List.of(root, target));
        var compact = new CompactSignalPropagationEngine(snapshot);

        assertAll(
                () -> assertFalse(root.connect(target)),
                () -> assertFalse(root.disconnect(disconnected)),
                () -> assertEquals(compiledVersion, root.getTopologyVersion()),
                () -> assertTrue(snapshot.isCurrent()));

        root.connect(disconnected);
        assertAll(
                () -> assertFalse(snapshot.isCurrent()),
                () -> assertThrows(IllegalStateException.class,
                        () -> compact.propagate(
                                root,
                                signal(1.0),
                                (source, input) -> NodeProcessingResult.noOutput(),
                                PropagationConfig.routeAll(1, 0))));

        var recompiled = new CompactSignalPropagationEngine(
                CompactGraphSnapshot.compile(List.of(target, disconnected, root)));
        assertEquivalent(
                recompiled,
                root,
                signal(1.0),
                (source, input) -> NodeProcessingResult.noOutput(),
                PropagationConfig.routeAll(2, 1));
    }

    @Test
    void rejectsInvalidSourceCollectionsAndNonCanonicalStartInstances() {
        var root = node(0L);
        var target = node(1L);
        root.connect(target);
        var duplicateRoot = node(0L);
        var outside = node(2L);
        var unclosed = node(3L);
        unclosed.connect(outside);
        var withNull = new ArrayList<Node>();
        withNull.add(root);
        withNull.add(null);

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> CompactGraphSnapshot.compile(null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> CompactGraphSnapshot.compile(List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> CompactGraphSnapshot.compile(List.of(root, duplicateRoot, target))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> CompactGraphSnapshot.compile(List.of(unclosed))),
                () -> assertThrows(NullPointerException.class,
                        () -> CompactGraphSnapshot.compile(withNull)));

        var compact = new CompactSignalPropagationEngine(
                CompactGraphSnapshot.compile(List.of(root, target)));
        assertThrows(IllegalArgumentException.class,
                () -> compact.propagate(
                        duplicateRoot,
                        signal(1.0),
                        (source, input) -> NodeProcessingResult.noOutput(),
                        PropagationConfig.routeAll(1, 0)));
    }

    @Test
    void exposesLiveNodeStateWithoutInvalidatingTheTopology() {
        var root = node(0L);
        var target = node(1L);
        root.connect(target);
        var snapshot = CompactGraphSnapshot.compile(List.of(target, root));
        var compact = new CompactSignalPropagationEngine(snapshot);
        var updatedState = new FrequencyState(0.8, 20.0, 0.5);
        target.transition(updatedState);
        target.setEnergy(0.7);
        var observedTargetStates = new ArrayList<FrequencyState>();
        var observedTargetEnergies = new ArrayList<Double>();

        var result = compact.propagate(
                root,
                signal(1.0),
                (source, input) -> {
                    if (source.getId().equals(target.getId())) {
                        observedTargetStates.add(source.getFrequencyState());
                        observedTargetEnergies.add(source.getEnergy());
                    }
                    return source.getId().equals(root.getId())
                            ? new NodeProcessingResult(List.of(input))
                            : NodeProcessingResult.noOutput();
                },
                new PropagationConfig(2, 1, (source, destination, emitted) -> {
                    if (destination.getId().equals(target.getId())) {
                        observedTargetStates.add(destination.getFrequencyState());
                        observedTargetEnergies.add(destination.getEnergy());
                    }
                    return true;
                }));

        assertAll(
                () -> assertEquals(2, result.processedSteps()),
                () -> assertTrue(snapshot.isCurrent()),
                () -> assertEquals(List.of(updatedState, updatedState), observedTargetStates),
                () -> assertEquals(List.of(0.7, 0.7), observedTargetEnergies));
    }

    @Test
    void detectsTopologyMutationDuringProcessingOrRoutingBeforeReturningAResult() {
        var root = node(0L);
        var target = node(1L);
        var addedDuringProcessing = node(2L);
        root.connect(target);
        var processingSnapshot = CompactGraphSnapshot.compile(List.of(root, target));
        var processingCompact = new CompactSignalPropagationEngine(processingSnapshot);

        assertThrows(IllegalStateException.class,
                () -> processingCompact.propagate(
                        root,
                        signal(1.0),
                        (source, input) -> {
                            root.connect(addedDuringProcessing);
                            return NodeProcessingResult.noOutput();
                        },
                        PropagationConfig.routeAll(1, 0)));

        var routingSnapshot = CompactGraphSnapshot.compile(List.of(root, target, addedDuringProcessing));
        var routingCompact = new CompactSignalPropagationEngine(routingSnapshot);
        assertThrows(IllegalStateException.class,
                () -> routingCompact.propagate(
                        root,
                        signal(1.0),
                        (source, input) -> new NodeProcessingResult(List.of(input)),
                        new PropagationConfig(2, 1, (source, destination, emitted) -> {
                            root.disconnect(target);
                            return true;
                        })));
    }

    @Test
    void matchesReferenceAcrossDeterministicRandomizedTopologies() {
        for (long seed = 1L; seed <= 12L; seed++) {
            var random = new Random(seed);
            var nodes = new ArrayList<Node>();
            for (int index = 0; index < 9; index++) {
                nodes.add(node(seed * 100L + index));
            }
            for (int sourceIndex = 0; sourceIndex < nodes.size(); sourceIndex++) {
                int connectionAttempts = 1 + random.nextInt(3);
                for (int attempt = 0; attempt < connectionAttempts; attempt++) {
                    int targetIndex = random.nextInt(nodes.size());
                    if (targetIndex != sourceIndex) {
                        nodes.get(sourceIndex).connect(nodes.get(targetIndex));
                    }
                }
            }
            var compact = new CompactSignalPropagationEngine(CompactGraphSnapshot.compile(nodes));
            SignalRoutingPolicy policy = (seed & 1L) == 0L
                    ? SignalRoutingPolicy.routeAll()
                    : (source, target, emitted) ->
                    ((target.getId().getLeastSignificantBits() + emitted.frequencyState().amplitude()) % 2.0) >= 1.0;
            var config = new PropagationConfig(1 + random.nextInt(15), random.nextInt(4), policy);

            assertEquivalent(
                    compact,
                    nodes.getFirst(),
                    signal(0.5),
                    (source, input) -> {
                        long selector = source.getId().getLeastSignificantBits() % 3L;
                        if (selector == 0L) {
                            return NodeProcessingResult.noOutput();
                        }
                        if (selector == 1L) {
                            return new NodeProcessingResult(List.of(
                                    signal(input.frequencyState().amplitude() + 1.0)));
                        }
                        return new NodeProcessingResult(List.of(
                                signal(input.frequencyState().amplitude() + 1.0),
                                signal(input.frequencyState().amplitude() + 2.0)));
                    },
                    config);
        }
    }

    @Test
    void delegatesContextualPropagationToTheReferenceEngine() {
        var root = node(0L);
        var target = node(1L);
        root.connect(target);
        var compact = new CompactSignalPropagationEngine(
                CompactGraphSnapshot.compile(List.of(root, target)));
        var referenceContext = new CognitiveContext(new CognitiveBudget(10, 10, 10));
        var compactContext = new CognitiveContext(new CognitiveBudget(10, 10, 10));
        NodeProcessor processor = (source, input) -> source.getId().equals(root.getId())
                ? new NodeProcessingResult(List.of(input))
                : NodeProcessingResult.noOutput();
        var config = PropagationConfig.routeAll(4, 2);

        var expected = reference.propagate(root, signal(1.0), processor, config, referenceContext);
        var actual = compact.propagate(root, signal(1.0), processor, config, compactContext);

        assertAll(
                () -> assertEquals(expected, actual),
                () -> assertEquals(
                        referenceContext.complete(CognitiveCycleOutcome.SUCCESS),
                        compactContext.complete(CognitiveCycleOutcome.SUCCESS)));
    }

    private void assertEquivalent(
            CompactSignalPropagationEngine compact,
            Node startNode,
            Signal input,
            OutputStrategy strategy,
            PropagationConfig config) {
        var referenceVisits = new ArrayList<UUID>();
        var compactVisits = new ArrayList<UUID>();
        NodeProcessor referenceProcessor = (source, received) -> {
            referenceVisits.add(source.getId());
            return strategy.process(source, received);
        };
        NodeProcessor compactProcessor = (source, received) -> {
            compactVisits.add(source.getId());
            return strategy.process(source, received);
        };

        var expected = reference.propagate(startNode, input, referenceProcessor, config);
        var actual = compact.propagate(startNode, input, compactProcessor, config);

        assertAll(
                () -> assertEquals(expected, actual),
                () -> assertEquals(referenceVisits, compactVisits));
    }

    private Node node(long id) {
        return node(id, FrequencyState.ZERO);
    }

    private Node node(long id, FrequencyState state) {
        return new Node.Builder()
                .id(new UUID(0L, id))
                .type(NodeType.PROCESSOR)
                .frequencyState(state)
                .build();
    }

    private Signal signal(double amplitude) {
        return new Signal(
                SignalKind.INTERMEDIATE,
                new FrequencyState(amplitude, 10.0, 0.0));
    }

    @FunctionalInterface
    private interface OutputStrategy {

        NodeProcessingResult process(NodeView source, Signal input);
    }
}
