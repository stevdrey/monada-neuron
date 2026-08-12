package monada.neuron.aeon;

import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicAeonCoordinatorTest {

    private final SignalPropagationEngine graphEngine = new DeterministicSignalPropagationEngine();
    private final AeonCoordinator coordinator = new DeterministicAeonCoordinator(graphEngine);

    @Test
    void emptyInputSucceedsForAnEmptyAeonWithoutCallingTheEngine() {
        var calls = new AtomicInteger();
        SignalPropagationEngine unusedEngine = (start, input, processor, config) -> {
            calls.incrementAndGet();
            throw new AssertionError("engine must not be called");
        };
        var emptyCoordinator = new DeterministicAeonCoordinator(unusedEngine);

        var result = emptyCoordinator.coordinate(
                new Aeon(uuid(100), AeonPurpose.PERCEPTION),
                List.of(),
                (node, input) -> NodeProcessingResult.noOutput(),
                PropagationConfig.routeAll(1, 0));

        assertAll(
                () -> assertSame(AeonCoordinationResult.empty(), result),
                () -> assertEquals(0, calls.get()));
    }

    @Test
    void validatesEveryStartMembershipBeforeProcessing() {
        var aeon = aeonWith(node(uuid(1)));
        var calls = new AtomicInteger();
        SignalPropagationEngine countingEngine = (start, input, processor, config) -> {
            calls.incrementAndGet();
            return new PropagationResult(List.of(), 0, false, false);
        };
        var validatingCoordinator = new DeterministicAeonCoordinator(countingEngine);
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

    @Test
    void coordinatesInputsSequentiallyFromCanonicalMembers() {
        var canonicalFirst = node(uuid(1));
        var canonicalSecond = node(uuid(2));
        var aeon = aeonWith(canonicalFirst, canonicalSecond);
        var firstInput = new AeonInput(uuid(2), signal(2.0));
        var secondInput = new AeonInput(uuid(1), signal(1.0));
        var starts = new ArrayList<Node>();
        var received = new ArrayList<Signal>();
        SignalPropagationEngine recordingEngine = (start, input, processor, config) -> {
            starts.add(start);
            received.add(input);
            return new PropagationResult(List.of(input), 1, false, false);
        };
        var recordingCoordinator = new DeterministicAeonCoordinator(recordingEngine);

        var result = recordingCoordinator.coordinate(
                aeon,
                List.of(firstInput, secondInput),
                (node, input) -> NodeProcessingResult.noOutput(),
                PropagationConfig.routeAll(1, 0));

        assertAll(
                () -> assertEquals(List.of(canonicalSecond, canonicalFirst), starts),
                () -> assertEquals(List.of(firstInput.signal(), secondInput.signal()), received),
                () -> assertEquals(firstInput, result.inputResults().get(0).input()),
                () -> assertEquals(secondInput, result.inputResults().get(1).input()),
                () -> assertEquals(
                        List.of(firstInput.signal()),
                        result.inputResults().get(0).propagationResult().emittedSignals()));
    }

    @Test
    void confinesTraversalToMembersWithoutChangingTheNodeGraph() {
        var root = node(uuid(1));
        var member = node(uuid(2));
        var external = node(uuid(3));
        root.connect(external);
        root.connect(member);
        var aeon = aeonWith(root, member);
        var processedNodes = new ArrayList<UUID>();

        var result = coordinator.coordinate(
                aeon,
                List.of(new AeonInput(root.getId(), signal(1.0))),
                (node, input) -> {
                    processedNodes.add(node.getId());
                    return new NodeProcessingResult(List.of(input));
                },
                PropagationConfig.routeAll(10, 1));

        var propagation = result.inputResults().getFirst().propagationResult();
        assertAll(
                () -> assertEquals(List.of(root.getId(), member.getId()), processedNodes),
                () -> assertEquals(2, propagation.processedSteps()),
                () -> assertEquals(List.of(signal(1.0), signal(1.0)),
                        propagation.emittedSignals()),
                () -> assertFalse(propagation.stepLimitReached()),
                () -> assertFalse(propagation.hopLimitReached()),
                () -> assertTrue(root.getConnections().contains(member)),
                () -> assertTrue(root.getConnections().contains(external)),
                () -> assertEquals(2, root.getConnections().size()));
    }

    @Test
    void rejectsConnectedNodeObjectsThatOnlyShareACanonicalMemberUuid() {
        var root = node(uuid(1));
        var canonicalMember = node(uuid(2));
        var equalButDifferentTarget = node(uuid(2));
        root.connect(equalButDifferentTarget);
        var aeon = aeonWith(root, canonicalMember);
        var processedNodeIds = new ArrayList<UUID>();

        var result = coordinator.coordinate(
                aeon,
                List.of(new AeonInput(root.getId(), signal(1.0))),
                (node, input) -> {
                    processedNodeIds.add(node.getId());
                    return new NodeProcessingResult(List.of(input));
                },
                PropagationConfig.routeAll(10, 1));

        assertAll(
                () -> assertEquals(List.of(root.getId()), processedNodeIds),
                () -> assertEquals(1,
                        result.inputResults().getFirst().propagationResult().processedSteps()),
                () -> assertSame(
                        canonicalMember,
                        aeon.findMember(canonicalMember.getId()).orElseThrow()),
                () -> assertSame(equalButDifferentTarget, root.getConnections().iterator().next()));
    }

    @Test
    void zeroEnergyMembersAreProcessedAndNoOutputStopsOnlyThatBranch() {
        var root = node(uuid(1));
        var silent = node(uuid(2));
        var unreachable = node(uuid(3));
        root.connect(silent);
        silent.connect(unreachable);
        var aeon = aeonWith(root, silent, unreachable);
        var processedNodes = new ArrayList<UUID>();

        var result = coordinator.coordinate(
                aeon,
                List.of(new AeonInput(root.getId(), signal(1.0))),
                (node, input) -> {
                    processedNodes.add(node.getId());
                    return node.getId().equals(silent.getId())
                            ? NodeProcessingResult.noOutput()
                            : new NodeProcessingResult(List.of(input));
                },
                PropagationConfig.routeAll(10, 10));

        var propagation = result.inputResults().getFirst().propagationResult();
        assertAll(
                () -> assertEquals(0.0, root.getEnergy()),
                () -> assertEquals(0.0, silent.getEnergy()),
                () -> assertEquals(List.of(root.getId(), silent.getId()), processedNodes),
                () -> assertEquals(List.of(signal(1.0)), propagation.emittedSignals()),
                () -> assertEquals(2, propagation.processedSteps()));
    }

    @Test
    void preservesPropagationLimitsAndProducesDeterministicReplay() {
        var first = node(uuid(1));
        var second = node(uuid(2));
        first.connect(second);
        second.connect(first);
        var aeon = aeonWith(first, second);
        var inputs = List.of(
                new AeonInput(first.getId(), signal(1.0)),
                new AeonInput(second.getId(), signal(2.0)));
        NodeProcessor processor = (node, input) -> new NodeProcessingResult(List.of(input));
        var config = PropagationConfig.routeAll(2, 10);

        var firstReplay = coordinator.coordinate(aeon, inputs, processor, config);
        var secondReplay = coordinator.coordinate(aeon, inputs, processor, config);

        assertAll(
                () -> assertEquals(firstReplay, secondReplay),
                () -> assertEquals(2, firstReplay.inputResults().size()),
                () -> assertTrue(firstReplay.inputResults().stream()
                        .allMatch(result -> result.propagationResult().stepLimitReached())),
                () -> assertTrue(firstReplay.inputResults().stream()
                        .allMatch(result -> result.propagationResult().processedSteps() == 2)));
    }

    @Test
    void propagatesProcessingAndRoutingFailuresWithoutReturningPartialResults() {
        var root = node(uuid(1));
        var target = node(uuid(2));
        root.connect(target);
        var aeon = aeonWith(root, target);
        var input = new AeonInput(root.getId(), signal(1.0));
        var processingFailure = new IllegalStateException("processing failed");
        var routingFailure = new IllegalArgumentException("routing failed");

        var actualProcessingFailure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.coordinate(
                        aeon,
                        List.of(input),
                        (node, signal) -> {
                            throw processingFailure;
                        },
                        PropagationConfig.routeAll(2, 1)));
        var actualRoutingFailure = assertThrows(
                IllegalArgumentException.class,
                () -> coordinator.coordinate(
                        aeon,
                        List.of(input),
                        (node, signal) -> new NodeProcessingResult(List.of(signal)),
                        new PropagationConfig(2, 1, (source, destination, signal) -> {
                            throw routingFailure;
                        })));

        assertAll(
                () -> assertSame(processingFailure, actualProcessingFailure),
                () -> assertSame(routingFailure, actualRoutingFailure));
    }

    @Test
    void laterInputFailureDoesNotExposeEarlierPartialResults() {
        var root = node(uuid(1));
        var aeon = aeonWith(root);
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("second input failed");
        SignalPropagationEngine failingEngine = (start, input, processor, config) -> {
            if (calls.incrementAndGet() == 2) {
                throw failure;
            }
            return new PropagationResult(List.of(input), 1, false, false);
        };
        var failingCoordinator = new DeterministicAeonCoordinator(failingEngine);
        var inputs = List.of(
                new AeonInput(root.getId(), signal(1.0)),
                new AeonInput(root.getId(), signal(2.0)));

        var actual = assertThrows(
                IllegalStateException.class,
                () -> failingCoordinator.coordinate(
                        aeon,
                        inputs,
                        (node, input) -> NodeProcessingResult.noOutput(),
                        PropagationConfig.routeAll(1, 0)));

        assertAll(
                () -> assertSame(failure, actual),
                () -> assertEquals(2, calls.get()));
    }

    @Test
    void rejectsNullArgumentsAndNullPropagationResults() {
        var root = node(uuid(1));
        var aeon = aeonWith(root);
        var inputs = List.of(new AeonInput(root.getId(), signal(1.0)));
        NodeProcessor processor = (node, input) -> NodeProcessingResult.noOutput();
        var config = PropagationConfig.routeAll(1, 0);
        var nullResultCoordinator = new DeterministicAeonCoordinator(
                (start, input, behavior, propagationConfig) -> null);
        var inputsWithNull = new ArrayList<AeonInput>();
        inputsWithNull.add(null);

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new DeterministicAeonCoordinator(null)),
                () -> assertThrows(NullPointerException.class,
                        () -> coordinator.coordinate(null, inputs, processor, config)),
                () -> assertThrows(NullPointerException.class,
                        () -> coordinator.coordinate(aeon, null, processor, config)),
                () -> assertThrows(NullPointerException.class,
                        () -> coordinator.coordinate(aeon, inputsWithNull, processor, config)),
                () -> assertThrows(NullPointerException.class,
                        () -> coordinator.coordinate(aeon, inputs, null, config)),
                () -> assertThrows(NullPointerException.class,
                        () -> coordinator.coordinate(aeon, inputs, processor, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> nullResultCoordinator.coordinate(aeon, inputs, processor, config)));
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
