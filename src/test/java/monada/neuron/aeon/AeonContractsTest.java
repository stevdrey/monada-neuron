package monada.neuron.aeon;

import monada.neuron.model.FrequencyState;
import monada.neuron.runtime.graph.PropagationResult;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AeonContractsTest {

    @Test
    void inputRequiresExplicitNodeIdentityAndSignal() {
        var id = uuid(1);
        var signal = signal(1.0);
        var input = new AeonInput(id, signal);

        assertAll(
                () -> assertEquals(id, input.startNodeId()),
                () -> assertSame(signal, input.signal()),
                () -> assertThrows(NullPointerException.class, () -> new AeonInput(null, signal)),
                () -> assertThrows(NullPointerException.class, () -> new AeonInput(id, null)));
    }

    @Test
    void inputResultRequiresBothSidesOfTheAssociation() {
        var input = new AeonInput(uuid(1), signal(1.0));
        var propagation = new PropagationResult(List.of(signal(2.0)), 1, false, false);
        var result = new AeonInputResult(input, propagation);

        assertAll(
                () -> assertSame(input, result.input()),
                () -> assertSame(propagation, result.propagationResult()),
                () -> assertThrows(NullPointerException.class,
                        () -> new AeonInputResult(null, propagation)),
                () -> assertThrows(NullPointerException.class,
                        () -> new AeonInputResult(input, null)));
    }

    @Test
    void coordinationResultSnapshotsOrderedInputResults() {
        var first = inputResult(1, 1.0);
        var second = inputResult(2, 2.0);
        var source = new ArrayList<>(List.of(first, second, first));

        var result = new AeonCoordinationResult(source);
        source.clear();

        assertAll(
                () -> assertEquals(List.of(first, second, first), result.inputResults()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> result.inputResults().clear()),
                () -> assertThrows(NullPointerException.class,
                        () -> new AeonCoordinationResult(null)),
                () -> assertThrows(NullPointerException.class,
                        () -> new AeonCoordinationResult(List.of(first, null))));
    }

    @Test
    void emptyCoordinationResultIsShared() {
        assertAll(
                () -> assertSame(AeonCoordinationResult.empty(), AeonCoordinationResult.empty()),
                () -> assertTrue(AeonCoordinationResult.empty().inputResults().isEmpty()));
    }

    private AeonInputResult inputResult(long nodeId, double amplitude) {
        var input = new AeonInput(uuid(nodeId), signal(amplitude));
        return new AeonInputResult(
                input,
                new PropagationResult(List.of(input.signal()), 1, false, false));
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
