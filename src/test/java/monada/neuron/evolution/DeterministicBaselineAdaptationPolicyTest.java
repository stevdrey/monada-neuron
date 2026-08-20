package monada.neuron.evolution;

import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicBaselineAdaptationPolicyTest {

    private final DeterminationFixture fixture = new DeterminationFixture();

    @Test
    void positiveFeedbackReinforcesStateAndIncreasesEnergy() {
        var node = fixture.createNode(1.0, 10.0, 0.0, 5.0);
        var targetSignal = new Signal(SignalKind.FEEDBACK, new FrequencyState(3.0, 20.0, 1.0));
        var feedback = FeedbackInput.ofTarget(node.getId(), targetSignal, 1.0);

        var policy = new DeterministicBaselineAdaptationPolicy(new AdaptationConfig(
                0.5, 0.0, 100.0, 0.0, 1000.0, 0.0, 10000.0, 2.0));
        var decision = policy.adapt(node, feedback);

        assertAll(
                () -> assertTrue(decision.adapted()),
                () -> assertEquals(1.0, decision.previousState().amplitude()),
                () -> assertEquals(10.0, decision.previousState().frequency()),
                () -> assertEquals(0.0, decision.previousState().phase()),
                () -> assertEquals(5.0, decision.previousEnergy()),
                // 1.0 + 0.5 * 1.0 * (3.0 - 1.0) = 2.0
                () -> assertEquals(2.0, decision.newState().amplitude()),
                // 10.0 + 0.5 * 1.0 * (20.0 - 10.0) = 15.0
                () -> assertEquals(15.0, decision.newState().frequency()),
                // 0.0 + 0.5 * 1.0 * 1.0 = 0.5
                () -> assertEquals(0.5, decision.newState().phase()),
                // 5.0 + 0.5 * 1.0 * 2.0 = 6.0
                () -> assertEquals(6.0, decision.newEnergy()),
                // Node mutated
                () -> assertEquals(decision.newState(), node.getFrequencyState()),
                () -> assertEquals(decision.newEnergy(), node.getEnergy()),
                // History recorded
                () -> assertEquals(1, node.getHistory().size()),
                () -> assertEquals(decision.previousState(), node.getHistory().getFirst()));
    }

    @Test
    void negativeFeedbackAttenuatesAmplitudeAndReducesEnergy() {
        var node = fixture.createNode(10.0, 50.0, 0.0, 10.0);
        var targetSignal = new Signal(SignalKind.FEEDBACK, new FrequencyState(2.0, 20.0, 0.0));
        var feedback = FeedbackInput.ofTarget(node.getId(), targetSignal, -0.5);

        var policy = new DeterministicBaselineAdaptationPolicy(new AdaptationConfig(
                0.2, 0.0, 100.0, 0.0, 1000.0, 0.0, 10000.0, 5.0));
        var decision = policy.adapt(node, feedback);

        assertAll(
                () -> assertTrue(decision.adapted()),
                () -> assertEquals(10.0, decision.previousState().amplitude()),
                // 10.0 * (1.0 + 0.2 * (-0.5)) = 10.0 * 0.9 = 9.0
                () -> assertEquals(9.0, decision.newState().amplitude()),
                // Energy: 10.0 + 0.2 * (-0.5) * 5.0 = 10.0 - 0.5 = 9.5
                () -> assertEquals(9.5, decision.newEnergy()),
                () -> assertEquals(decision.newState(), node.getFrequencyState()),
                () -> assertEquals(decision.newEnergy(), node.getEnergy()));
    }

    @Test
    void neutralFeedbackLeavesNodeUnchanged() {
        var node = fixture.createNode(5.0, 20.0, 1.0, 10.0);
        var feedback = FeedbackInput.ofScore(node.getId(), 0.0);

        var policy = new DeterministicBaselineAdaptationPolicy();
        var decision = policy.adapt(node, feedback);

        assertAll(
                () -> assertFalse(decision.adapted()),
                () -> assertEquals(decision.previousState(), decision.newState()),
                () -> assertEquals(decision.previousEnergy(), decision.newEnergy()),
                () -> assertEquals(5.0, node.getFrequencyState().amplitude()),
                () -> assertEquals(10.0, node.getEnergy()),
                () -> assertTrue(node.getHistory().isEmpty()));
    }

    @Test
    void scalarFeedbackAdjustsAmplitudeAndEnergyWithoutAlteringFrequencyOrPhase() {
        var node = fixture.createNode(10.0, 440.0, 0.5, 20.0);
        var feedback = FeedbackInput.ofScore(node.getId(), 0.5);

        var policy = new DeterministicBaselineAdaptationPolicy(new AdaptationConfig(
                0.1, 0.0, 100.0, 0.0, 1000.0, 0.0, 10000.0, 2.0));
        var decision = policy.adapt(node, feedback);

        assertAll(
                () -> assertTrue(decision.adapted()),
                // 10.0 * (1 + 0.1 * 0.5) = 10.5
                () -> assertEquals(10.5, decision.newState().amplitude()),
                () -> assertEquals(440.0, decision.newState().frequency()),
                () -> assertEquals(0.5, decision.newState().phase()),
                // 20.0 + 0.1 * 0.5 * 2.0 = 20.1
                () -> assertEquals(20.1, decision.newEnergy()));
    }

    @Test
    void repeatedPositiveFeedbackAsymptoticallyConvergesToTarget() {
        var node = fixture.createNode(0.0, 0.0, 0.0, 0.0);
        var targetSignal = new Signal(SignalKind.FEEDBACK, new FrequencyState(10.0, 100.0, 1.5));
        var feedback = FeedbackInput.ofTarget(node.getId(), targetSignal, 1.0);

        var policy = new DeterministicBaselineAdaptationPolicy(new AdaptationConfig(
                0.2, 0.0, 100.0, 0.0, 1000.0, 0.0, 10000.0, 1.0));

        for (int step = 0; step < 25; step++) {
            policy.adapt(node, feedback);
        }

        assertAll(
                // 10.0 * (1 - 0.8^25) = 9.9622
                () -> assertEquals(9.9622, node.getFrequencyState().amplitude(), 1e-3),
                // 100.0 * (1 - 0.8^25) = 99.622
                () -> assertEquals(99.622, node.getFrequencyState().frequency(), 1e-3),
                // 1.5 * (1 - 0.8^25) = 1.4943
                () -> assertEquals(1.4943, node.getFrequencyState().phase(), 1e-3),
                () -> assertEquals(25, node.getHistory().size()));
    }

    @Test
    void clampingEnforcesMinAndMaxBounds() {
        var node = fixture.createNode(95.0, 950.0, 0.0, 995.0);
        var targetSignal = new Signal(SignalKind.FEEDBACK, new FrequencyState(200.0, 2000.0, 0.0));
        var feedback = FeedbackInput.ofTarget(node.getId(), targetSignal, 1.0);

        var policy = new DeterministicBaselineAdaptationPolicy(new AdaptationConfig(
                1.0, 0.0, 100.0, 0.0, 1000.0, 0.0, 1000.0, 50.0));
        var decision = policy.adapt(node, feedback);

        assertAll(
                () -> assertEquals(100.0, decision.newState().amplitude()),
                () -> assertEquals(1000.0, decision.newState().frequency()),
                () -> assertEquals(1000.0, decision.newEnergy()));

        // Negative clamping down to 0.0 floor
        var lowNode = fixture.createNode(1.0, 10.0, 0.0, 0.5);
        var negFeedback = FeedbackInput.ofScore(lowNode.getId(), -1.0);
        var negPolicy = new DeterministicBaselineAdaptationPolicy(new AdaptationConfig(
                1.0, 0.0, 100.0, 0.0, 1000.0, 0.0, 1000.0, 50.0));
        var negDecision = negPolicy.adapt(lowNode, negFeedback);

        assertAll(
                () -> assertEquals(0.0, negDecision.newState().amplitude()),
                () -> assertEquals(0.0, negDecision.newEnergy()));
    }

    @Test
    void rejectsMismatchedTargetNodeId() {
        var node = fixture.createNode(1.0, 10.0, 0.0, 1.0);
        var wrongId = UUID.randomUUID();
        var feedback = FeedbackInput.ofScore(wrongId, 1.0);

        var policy = new DeterministicBaselineAdaptationPolicy();

        assertThrows(IllegalArgumentException.class, () -> policy.adapt(node, feedback));
    }

    @Test
    void deterministicReplayProducesBitExactTransitions() {
        var id = UUID.randomUUID();
        var nodeA = new Node.Builder()
                .id(id)
                .frequencyState(new FrequencyState(5.0, 50.0, 0.5))
                .energy(10.0)
                .type(NodeType.PROCESSOR)
                .build();

        var nodeB = new Node.Builder()
                .id(id)
                .frequencyState(new FrequencyState(5.0, 50.0, 0.5))
                .energy(10.0)
                .type(NodeType.PROCESSOR)
                .build();

        var target = new Signal(SignalKind.FEEDBACK, new FrequencyState(8.0, 120.0, 2.0));
        var feedback = FeedbackInput.ofTarget(id, target, 0.7);

        var policy = new DeterministicBaselineAdaptationPolicy();

        for (int i = 0; i < 5; i++) {
            var decisionA = policy.adapt(nodeA, feedback);
            var decisionB = policy.adapt(nodeB, feedback);
            assertEquals(decisionA, decisionB);
        }

        assertEquals(nodeA.getFrequencyState(), nodeB.getFrequencyState());
        assertEquals(nodeA.getEnergy(), nodeB.getEnergy());
        assertEquals(nodeA.getHistory(), nodeB.getHistory());
    }

    @Test
    void noOpPolicyLeavesNodeUntouched() {
        var node = fixture.createNode(5.0, 20.0, 1.0, 10.0);
        var target = new Signal(SignalKind.FEEDBACK, new FrequencyState(8.0, 120.0, 2.0));
        var feedback = FeedbackInput.ofTarget(node.getId(), target, 1.0);

        var policy = NoOpAdaptationPolicy.INSTANCE;
        var decision = policy.adapt(node, feedback);

        assertAll(
                () -> assertFalse(decision.adapted()),
                () -> assertEquals(decision.previousState(), decision.newState()),
                () -> assertEquals(decision.previousEnergy(), decision.newEnergy()),
                () -> assertEquals(5.0, node.getFrequencyState().amplitude()),
                () -> assertEquals(10.0, node.getEnergy()),
                () -> assertTrue(node.getHistory().isEmpty()));
    }

    private static class DeterminationFixture {
        Node createNode(double amplitude, double frequency, double phase, double energy) {
            return new Node.Builder()
                    .frequencyState(new FrequencyState(amplitude, frequency, phase))
                    .energy(energy)
                    .type(NodeType.PROCESSOR)
                    .build();
        }
    }
}
