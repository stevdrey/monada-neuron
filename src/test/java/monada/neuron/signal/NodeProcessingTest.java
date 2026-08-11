package monada.neuron.signal;

import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeProcessingTest {

    @Test
    void processorEmitsExplicitOutputWithoutMutatingNode() {
        var node = new Node.Builder()
                .type(NodeType.PROCESSOR)
                .frequencyState(new FrequencyState(0.5, 20.0, 0.25))
                .energy(2.0)
                .build();
        var input = signal(SignalKind.OBSERVATION, 1.0);
        var output = signal(SignalKind.INTERMEDIATE, 2.0);
        var originalState = node.getFrequencyState();
        var originalEnergy = node.getEnergy();

        NodeProcessor processor = (nodeView, received) -> {
            assertEquals(node.getId(), nodeView.getId());
            assertSame(input, received);
            return new NodeProcessingResult(List.of(output));
        };

        var result = processor.process(node, input);

        assertEquals(List.of(output), result.emittedSignals());
        assertSame(originalState, node.getFrequencyState());
        assertEquals(originalEnergy, node.getEnergy());
        assertTrue(node.getHistory().isEmpty());
    }

    @Test
    void noOutputReturnsSharedEmptyResult() {
        var first = NodeProcessingResult.noOutput();
        var second = NodeProcessingResult.noOutput();

        assertSame(first, second);
        assertTrue(first.emittedSignals().isEmpty());
    }

    @Test
    void resultSnapshotsAndPreservesEmissionOrderIncludingDuplicates() {
        var first = signal(SignalKind.INTERMEDIATE, 1.0);
        var second = signal(SignalKind.FEEDBACK, 2.0);
        var source = new ArrayList<>(List.of(first, second, first));

        var result = new NodeProcessingResult(source);
        source.clear();

        assertEquals(List.of(first, second, first), result.emittedSignals());
        assertThrows(UnsupportedOperationException.class,
                () -> result.emittedSignals().add(signal(SignalKind.INTERMEDIATE, 3.0)));
    }

    @Test
    void resultRejectsNullListAndElements() {
        assertThrows(NullPointerException.class, () -> new NodeProcessingResult(null));

        var signals = new ArrayList<Signal>();
        signals.add(signal(SignalKind.INTERMEDIATE, 1.0));
        signals.add(null);

        assertThrows(NullPointerException.class, () -> new NodeProcessingResult(signals));
    }

    @Test
    void processingFailurePropagatesToCaller() {
        var expected = new IllegalStateException("processing failed");
        NodeProcessor processor = (node, input) -> {
            throw expected;
        };
        var node = new Node.Builder().type(NodeType.PROCESSOR).build();

        var actual = assertThrows(
                IllegalStateException.class,
                () -> processor.process(node, signal(SignalKind.OBSERVATION, 1.0)));

        assertSame(expected, actual);
    }

    private Signal signal(SignalKind kind, double amplitude) {
        return new Signal(kind, new FrequencyState(amplitude, 10.0, 0.0));
    }
}
