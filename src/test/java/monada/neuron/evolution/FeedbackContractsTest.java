package monada.neuron.evolution;

import monada.neuron.model.FrequencyState;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackContractsTest {

    @Test
    void feedbackInputValidatesInvariantsAndProvidesFactories() {
        var nodeId = UUID.randomUUID();
        var observed = new Signal(SignalKind.OBSERVATION, new FrequencyState(1.0, 10.0, 0.0));
        var target = new Signal(SignalKind.FEEDBACK, new FrequencyState(2.0, 20.0, 0.5));

        var full = FeedbackInput.of(nodeId, observed, target, 0.8);
        var targetOnly = FeedbackInput.ofTarget(nodeId, target, 0.5);
        var scoreOnly = FeedbackInput.ofScore(nodeId, -0.2);

        assertAll(
                () -> assertEquals(nodeId, full.targetNodeId()),
                () -> assertEquals(observed, full.observedSignal()),
                () -> assertEquals(target, full.targetSignal()),
                () -> assertEquals(0.8, full.score()),
                () -> assertTrue(full.optionalObservedSignal().isPresent()),
                () -> assertTrue(full.optionalTargetSignal().isPresent()),
                () -> assertEquals(observed, full.optionalObservedSignal().get()),
                () -> assertEquals(target, full.optionalTargetSignal().get()),

                () -> assertNull(targetOnly.observedSignal()),
                () -> assertFalse(targetOnly.optionalObservedSignal().isPresent()),
                () -> assertEquals(target, targetOnly.targetSignal()),
                () -> assertEquals(0.5, targetOnly.score()),

                () -> assertNull(scoreOnly.observedSignal()),
                () -> assertNull(scoreOnly.targetSignal()),
                () -> assertFalse(scoreOnly.optionalObservedSignal().isPresent()),
                () -> assertFalse(scoreOnly.optionalTargetSignal().isPresent()),
                () -> assertEquals(-0.2, scoreOnly.score()));
    }

    @Test
    void feedbackInputRejectsInvalidInputs() {
        var nodeId = UUID.randomUUID();

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new FeedbackInput(null, null, null, 0.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FeedbackInput.ofScore(nodeId, 1.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FeedbackInput.ofScore(nodeId, -1.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FeedbackInput.ofScore(nodeId, Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FeedbackInput.ofScore(nodeId, Double.POSITIVE_INFINITY)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FeedbackInput.ofScore(nodeId, Double.NEGATIVE_INFINITY)));
    }

    @Test
    void adaptationDecisionValidatesInvariants() {
        var nodeId = UUID.randomUUID();
        var state1 = new FrequencyState(1.0, 10.0, 0.0);
        var state2 = new FrequencyState(2.0, 10.0, 0.0);

        var decision = new AdaptationDecision(nodeId, true, state1, state2, 1.0, 2.0);

        assertAll(
                () -> assertEquals(nodeId, decision.nodeId()),
                () -> assertTrue(decision.adapted()),
                () -> assertEquals(state1, decision.previousState()),
                () -> assertEquals(state2, decision.newState()),
                () -> assertEquals(1.0, decision.previousEnergy()),
                () -> assertEquals(2.0, decision.newEnergy()),

                () -> assertThrows(NullPointerException.class,
                        () -> new AdaptationDecision(null, true, state1, state2, 1.0, 2.0)),
                () -> assertThrows(NullPointerException.class,
                        () -> new AdaptationDecision(nodeId, true, null, state2, 1.0, 2.0)),
                () -> assertThrows(NullPointerException.class,
                        () -> new AdaptationDecision(nodeId, true, state1, null, 1.0, 2.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationDecision(nodeId, true, state1, state2, -1.0, 2.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationDecision(nodeId, true, state1, state2, 1.0, -2.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationDecision(nodeId, true, state1, state2, Double.NaN, 2.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationDecision(nodeId, true, state1, state2, 1.0, Double.POSITIVE_INFINITY)));
    }

    @Test
    void adaptationConfigValidatesInvariants() {
        var valid = AdaptationConfig.DEFAULT;

        assertAll(
                () -> assertEquals(0.1, valid.learningRate()),
                () -> assertEquals(0.0, valid.minAmplitude()),
                () -> assertEquals(100.0, valid.maxAmplitude()),
                () -> assertEquals(0.0, valid.minEnergy()),
                () -> assertEquals(1000.0, valid.maxEnergy()),
                () -> assertEquals(0.0, valid.minFrequency()),
                () -> assertEquals(10000.0, valid.maxFrequency()),
                () -> assertEquals(1.0, valid.energyStep()),

                // Invalid learning rates
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationConfig(0.0, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0, 1.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationConfig(-0.1, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0, 1.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationConfig(1.1, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0, 1.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationConfig(Double.NaN, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0, 1.0)),

                // Inconsistent min/max bounds
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationConfig(0.1, 10.0, 5.0, 0.0, 10.0, 0.0, 10.0, 1.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationConfig(0.1, -1.0, 5.0, 0.0, 10.0, 0.0, 10.0, 1.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationConfig(0.1, 0.0, 10.0, 10.0, 5.0, 0.0, 10.0, 1.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationConfig(0.1, 0.0, 10.0, 0.0, 10.0, 10.0, 5.0, 1.0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AdaptationConfig(0.1, 0.0, 10.0, 0.0, 10.0, 0.0, 10.0, 0.0)));
    }
}
