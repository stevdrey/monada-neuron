package monada.neuron.runtime.state;

import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
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

class NodeStateSnapshotTest {

    @Test
    void compilesUuidSortedSnapshotsAndCopiesOnlySelectedState() {
        var high = node(9L, new FrequencyState(0.9, 90.0, 0.9), 0.9);
        var low = node(1L, new FrequencyState(0.1, 10.0, 0.1), 0.1);
        var middle = node(5L, new FrequencyState(0.5, 50.0, 0.5), 0.5);

        try (var heap = NodeStateSnapshot.compileOnHeap(List.of(high, low, middle));
                var ffm = NodeStateSnapshot.compileOffHeap(List.of(high, low, middle))) {
            assertAll(
                    () -> assertEquals(3, heap.nodeCount()),
                    () -> assertEquals(0, heap.denseIndexOf(low)),
                    () -> assertEquals(1, heap.denseIndexOf(middle)),
                    () -> assertEquals(2, heap.denseIndexOf(high)),
                    () -> assertEquals(new FrequencyState(0.1, 10.0, 0.1), heap.stateStore().stateAt(0)),
                    () -> assertEquals(0.5, heap.stateStore().energyAt(1)),
                    () -> assertEquals(heap.stateStore().stateAt(2), ffm.stateStore().stateAt(2)),
                    () -> assertEquals(3L * NodeStateStoreSupport.BYTES_PER_NODE,
                            heap.stateStore().logicalBytes()),
                    () -> assertEquals(0L, heap.stateStore().offHeapCommittedBytes()),
                    () -> assertEquals(3L * NodeStateStoreSupport.BYTES_PER_NODE,
                            ffm.stateStore().offHeapCommittedBytes()));

            var replacement = new FrequencyState(0.7, 70.0, 0.7);
            heap.stateStore().setState(1, replacement, 0.7);

            assertAll(
                    () -> assertEquals(replacement, heap.stateStore().stateAt(1)),
                    () -> assertEquals(0.7, heap.stateStore().energyAt(1)),
                    () -> assertEquals(new FrequencyState(0.5, 50.0, 0.5), middle.getFrequencyState()),
                    () -> assertEquals(0.5, middle.getEnergy()),
                    () -> assertTrue(middle.getHistory().isEmpty()));
        }
    }

    @Test
    void preservesObjectHeapAndFfmEquivalenceAcrossDeterministicUpdates() {
        var nodes = new ArrayList<Node>();
        for (int index = 0; index < 32; index++) {
            nodes.add(node(index, new FrequencyState(0.1 + index, 10.0 + index, index / 10.0), 0.2 + index));
        }

        try (var heap = NodeStateSnapshot.compileOnHeap(nodes);
                var ffm = NodeStateSnapshot.compileOffHeap(nodes)) {
            var random = new Random(42L);
            for (int operation = 0; operation < 200; operation++) {
                int index = random.nextInt(nodes.size());
                var state = new FrequencyState(
                        random.nextDouble() * 10.0,
                        random.nextDouble() * 100.0,
                        random.nextDouble() * 2.0 * StrictMath.PI);
                double energy = random.nextDouble() * 5.0;

                nodes.get(index).transition(state);
                nodes.get(index).setEnergy(energy);
                heap.stateStore().setState(index, state, energy);
                ffm.stateStore().setState(index, state, energy);
            }

            for (int index = 0; index < nodes.size(); index++) {
                int expectedIndex = index;
                var expected = nodes.get(expectedIndex);
                assertAll(
                        () -> assertEquals(expected.getFrequencyState(), heap.stateStore().stateAt(expectedIndex)),
                        () -> assertEquals(expected.getFrequencyState(), ffm.stateStore().stateAt(expectedIndex)),
                        () -> assertEquals(expected.getEnergy(), heap.stateStore().energyAt(expectedIndex)),
                        () -> assertEquals(expected.getEnergy(), ffm.stateStore().energyAt(expectedIndex)));
            }
        }
    }

    @Test
    void rejectsInvalidSnapshotInputAndNonCanonicalNodes() {
        var first = node(1L, FrequencyState.ZERO, 0.0);
        var duplicateId = node(1L, FrequencyState.ZERO, 0.0);
        var nullNodes = new ArrayList<Node>();
        nullNodes.add(first);
        nullNodes.add(null);

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> NodeStateSnapshot.compileOnHeap(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> NodeStateSnapshot.compileOnHeap(List.of())),
                () -> assertThrows(NullPointerException.class, () -> NodeStateSnapshot.compileOnHeap(nullNodes)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> NodeStateSnapshot.compileOnHeap(List.of(first, duplicateId))));

        try (var snapshot = NodeStateSnapshot.compileOnHeap(List.of(first))) {
            assertThrows(IllegalArgumentException.class, () -> snapshot.denseIndexOf(duplicateId));
        }
    }

    @Test
    void validatesStoreBoundariesAndMakesCloseIdempotent() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new HeapNodeStateStore(0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new FfmNodeStateStore(-1)),
                () -> assertEquals((long) Integer.MAX_VALUE * NodeStateStoreSupport.BYTES_PER_NODE,
                        NodeStateStoreSupport.logicalBytesFor(Integer.MAX_VALUE)));

        assertClosedAccess(new HeapNodeStateStore(1));
        assertClosedAccess(new FfmNodeStateStore(1));
    }

    private void assertClosedAccess(NodeStateStore store) {
        store.close();
        store.close();

        assertAll(
                () -> assertFalse(store.isOpen()),
                () -> assertThrows(IllegalStateException.class, store::size),
                () -> assertThrows(IllegalStateException.class, store::logicalBytes),
                () -> assertThrows(IllegalStateException.class, store::offHeapCommittedBytes),
                () -> assertThrows(IllegalStateException.class, () -> store.amplitudeAt(0)),
                () -> assertThrows(IllegalStateException.class,
                        () -> store.setState(0, FrequencyState.ZERO, 0.0)));
    }

    private Node node(long id, FrequencyState state, double energy) {
        return new Node.Builder()
                .id(new UUID(0L, id))
                .type(NodeType.PROCESSOR)
                .frequencyState(state)
                .energy(energy)
                .build();
    }
}
