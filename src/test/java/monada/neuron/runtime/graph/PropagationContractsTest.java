package monada.neuron.runtime.graph;

import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.resonance.ResonanceMetric;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropagationContractsTest {

    @Test
    void configurationValidatesBoundsAndProvidesSharedRouteAllPolicy() {
        var first = PropagationConfig.routeAll(1, 0);
        var second = PropagationConfig.routeAll(2, 3);

        assertAll(
                () -> assertSame(first.routingPolicy(), second.routingPolicy()),
                () -> assertTrue(first.routingPolicy().shouldRoute(
                        node(FrequencyState.ZERO), node(FrequencyState.ZERO), signal(1.0))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PropagationConfig.routeAll(0, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PropagationConfig.routeAll(-1, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PropagationConfig.routeAll(1, -1)),
                () -> assertThrows(NullPointerException.class,
                        () -> new PropagationConfig(1, 0, null)));
    }

    @Test
    void resultSnapshotsEmissionsAndValidatesConstruction() {
        var first = signal(1.0);
        var second = signal(2.0);
        var source = new ArrayList<>(List.of(first, second, first));

        var result = new PropagationResult(source, 2, true, false);
        source.clear();

        assertAll(
                () -> assertEquals(List.of(first, second, first), result.emittedSignals()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> result.emittedSignals().add(signal(3.0))),
                () -> assertThrows(NullPointerException.class,
                        () -> new PropagationResult(null, 0, false, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PropagationResult(List.of(), -1, false, false)));

        var withNull = new ArrayList<Signal>();
        withNull.add(first);
        withNull.add(null);
        assertThrows(NullPointerException.class,
                () -> new PropagationResult(withNull, 0, false, false));
    }

    @Test
    void resonanceThresholdIsInclusiveAndUsesInjectedMetricArguments() {
        var source = node(new FrequencyState(8.0, 80.0, 0.8));
        var targetState = new FrequencyState(2.0, 20.0, 0.2);
        var target = node(targetState);
        var emitted = signal(1.0);
        ResonanceMetric metric = (first, second) -> {
            assertSame(emitted.frequencyState(), first);
            assertSame(targetState, second);
            return 0.75;
        };

        var inclusive = new ResonanceThresholdRoutingPolicy(metric, 0.75);
        var above = new ResonanceThresholdRoutingPolicy(metric, 0.750_001);

        assertAll(
                () -> assertEquals(0.75, inclusive.minimumScore()),
                () -> assertTrue(inclusive.shouldRoute(source, target, emitted)),
                () -> assertFalse(above.shouldRoute(source, target, emitted)));
    }

    @Test
    void scalarResonanceRoutingHandlesThresholdBoundsAndSilence() {
        var activeState = new FrequencyState(1.0, 10.0, 0.0);
        var source = node(activeState);
        var activeTarget = node(activeState);
        var silentTarget = node(FrequencyState.ZERO);
        var activeSignal = new Signal(SignalKind.INTERMEDIATE, activeState);
        var silentSignal = new Signal(SignalKind.INTERMEDIATE, FrequencyState.ZERO);
        var exactOnly = new ResonanceThresholdRoutingPolicy(new ScalarResonanceMetric(), 1.0);
        var acceptZero = new ResonanceThresholdRoutingPolicy(new ScalarResonanceMetric(), 0.0);
        var rejectZero = new ResonanceThresholdRoutingPolicy(new ScalarResonanceMetric(), 0.000_001);

        assertAll(
                () -> assertTrue(exactOnly.shouldRoute(source, activeTarget, activeSignal)),
                () -> assertTrue(acceptZero.shouldRoute(source, silentTarget, silentSignal)),
                () -> assertFalse(rejectZero.shouldRoute(source, silentTarget, silentSignal)));
    }

    @Test
    void resonancePolicyRejectsInvalidConstructionAndNullInputs() {
        ResonanceMetric metric = new ScalarResonanceMetric();
        var policy = new ResonanceThresholdRoutingPolicy(metric, 0.5);
        var node = node(new FrequencyState(1.0, 10.0, 0.0));
        var signal = signal(1.0);

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new ResonanceThresholdRoutingPolicy(null, 0.5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ResonanceThresholdRoutingPolicy(metric, -0.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ResonanceThresholdRoutingPolicy(metric, 1.1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ResonanceThresholdRoutingPolicy(metric, Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ResonanceThresholdRoutingPolicy(
                                metric, Double.POSITIVE_INFINITY)),
                () -> assertThrows(NullPointerException.class,
                        () -> policy.shouldRoute(null, node, signal)),
                () -> assertThrows(NullPointerException.class,
                        () -> policy.shouldRoute(node, null, signal)),
                () -> assertThrows(NullPointerException.class,
                        () -> policy.shouldRoute(node, node, null)));
    }

    private Node node(FrequencyState state) {
        return new Node.Builder()
                .type(NodeType.PROCESSOR)
                .frequencyState(state)
                .build();
    }

    private Signal signal(double amplitude) {
        return new Signal(
                SignalKind.INTERMEDIATE,
                new FrequencyState(amplitude, 10.0, 0.0));
    }
}
