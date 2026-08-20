package monada.neuron.evolution;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.context.CognitiveCycleOutcome;
import monada.neuron.context.CognitiveTraceEvent;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.CognitiveStageStatus;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptationCognitiveStageTest {

    @Test
    void adaptsTargetNodesAndRecordsTraceEntries() {
        var node1 = createNode(1.0, 10.0, 0.0, 5.0);
        var node2 = createNode(2.0, 20.0, 0.0, 10.0);
        var targetSignal = new Signal(SignalKind.FEEDBACK, new FrequencyState(5.0, 50.0, 1.0));

        var policy = new DeterministicBaselineAdaptationPolicy();
        var stage = new AdaptationCognitiveStage(policy, List.of(node1, node2));

        var monad = new PrimaryMonad(UUID.randomUUID());
        var context = new CognitiveContext(new CognitiveBudget(10, 10, 10));

        var result = stage.execute(monad, List.of(targetSignal), context);
        var snapshot = context.complete(CognitiveCycleOutcome.SUCCESS);

        assertAll(
                () -> assertEquals(CognitiveStageKind.ADAPTATION, stage.kind()),
                () -> assertEquals(CognitiveStageStatus.COMPLETED, result.status()),
                () -> assertEquals(2, result.decisions().size()),
                () -> assertTrue(result.decisions().get(0).adapted()),
                () -> assertTrue(result.decisions().get(1).adapted()),
                () -> assertEquals(List.of(targetSignal), result.outputSignals()),
                () -> assertEquals(2, snapshot.traceEntries().size()),
                () -> assertInstanceOf(CognitiveTraceEvent.NodeAdapted.class, snapshot.traceEntries().get(0).event()),
                () -> assertInstanceOf(CognitiveTraceEvent.NodeAdapted.class, snapshot.traceEntries().get(1).event()),
                () -> {
                    var event1 = (CognitiveTraceEvent.NodeAdapted) snapshot.traceEntries().get(0).event();
                    assertEquals(node1.getId(), event1.nodeId());
                    assertTrue(event1.adapted());
                });
    }

    @Test
    void supportsAeonConstructorAndAdaptsAllMembers() {
        var node1 = createNode(1.0, 10.0, 0.0, 5.0);
        var node2 = createNode(2.0, 20.0, 0.0, 10.0);
        var aeon = new Aeon(UUID.randomUUID(), AeonPurpose.EVOLUTION);
        aeon.addMember(node1);
        aeon.addMember(node2);

        var policy = new DeterministicBaselineAdaptationPolicy();
        var stage = new AdaptationCognitiveStage(policy, aeon);

        var monad = new PrimaryMonad(UUID.randomUUID());
        var context = new CognitiveContext(new CognitiveBudget(10, 10, 10));
        var feedbackSignal = new Signal(SignalKind.FEEDBACK, new FrequencyState(4.0, 40.0, 0.0));

        var result = stage.execute(monad, List.of(feedbackSignal), context);

        assertAll(
                () -> assertEquals(2, result.decisions().size()),
                () -> assertEquals(node1.getId(), result.decisions().get(0).nodeId()),
                () -> assertEquals(node2.getId(), result.decisions().get(1).nodeId()));
    }

    @Test
    void supportsCustomFeedbackMapper() {
        var node = createNode(10.0, 100.0, 0.0, 20.0);
        var stage = new AdaptationCognitiveStage(
                new DeterministicBaselineAdaptationPolicy(),
                List.of(node),
                (n, sig) -> FeedbackInput.ofScore(n.getId(), -0.5));

        var monad = new PrimaryMonad(UUID.randomUUID());
        var context = new CognitiveContext(new CognitiveBudget(10, 10, 10));
        var dummySignal = new Signal(SignalKind.OBSERVATION, FrequencyState.ZERO);

        var result = stage.execute(monad, List.of(dummySignal), context);

        assertAll(
                () -> assertEquals(1, result.decisions().size()),
                () -> assertTrue(result.decisions().getFirst().adapted()),
                // Amplitude should be attenuated because score is -0.5
                () -> assertTrue(result.decisions().getFirst().newState().amplitude() < 10.0));
    }

    @Test
    void emptyInputsOrTargetNodesReturnEmptyDecisions() {
        var node = createNode(1.0, 10.0, 0.0, 5.0);
        var policy = new DeterministicBaselineAdaptationPolicy();
        var stageEmptyNodes = new AdaptationCognitiveStage(policy, List.of());
        var stageEmptyInputs = new AdaptationCognitiveStage(policy, List.of(node));

        var monad = new PrimaryMonad(UUID.randomUUID());
        var context = new CognitiveContext(new CognitiveBudget(10, 10, 10));
        var signal = new Signal(SignalKind.OBSERVATION, FrequencyState.ZERO);

        var resultEmptyNodes = stageEmptyNodes.execute(monad, List.of(signal), context);
        var resultEmptyInputs = stageEmptyInputs.execute(monad, List.of(), context);

        assertAll(
                () -> assertTrue(resultEmptyNodes.decisions().isEmpty()),
                () -> assertEquals(List.of(signal), resultEmptyNodes.outputSignals()),
                () -> assertTrue(resultEmptyInputs.decisions().isEmpty()),
                () -> assertTrue(resultEmptyInputs.outputSignals().isEmpty()));
    }

    @Test
    void noOpPolicyInStageEmitsDecisionsWithoutMutations() {
        var node = createNode(1.0, 10.0, 0.0, 5.0);
        var stage = new AdaptationCognitiveStage(NoOpAdaptationPolicy.INSTANCE, List.of(node));

        var monad = new PrimaryMonad(UUID.randomUUID());
        var context = new CognitiveContext(new CognitiveBudget(10, 10, 10));
        var signal = new Signal(SignalKind.FEEDBACK, new FrequencyState(5.0, 50.0, 1.0));

        var result = stage.execute(monad, List.of(signal), context);

        assertAll(
                () -> assertEquals(1, result.decisions().size()),
                () -> assertFalse(result.decisions().getFirst().adapted()),
                () -> assertEquals(1.0, node.getFrequencyState().amplitude()),
                () -> assertEquals(5.0, node.getEnergy()),
                () -> assertTrue(node.getHistory().isEmpty()));
    }

    @Test
    void validatesArguments() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new AdaptationCognitiveStage(null, List.of())),
                () -> assertThrows(NullPointerException.class,
                        () -> new AdaptationCognitiveStage(NoOpAdaptationPolicy.INSTANCE, (List<Node>) null)),
                () -> assertThrows(NullPointerException.class,
                        () -> new AdaptationCognitiveStage(NoOpAdaptationPolicy.INSTANCE, (Aeon) null)));
    }

    private Node createNode(double amplitude, double frequency, double phase, double energy) {
        return new Node.Builder()
                .frequencyState(new FrequencyState(amplitude, frequency, phase))
                .energy(energy)
                .type(NodeType.PROCESSOR)
                .build();
    }
}
