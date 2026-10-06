package monada.neuron.evaluation.workload;

import monada.neuron.aeon.AeonPurpose;
import monada.neuron.evolution.NoOpAdaptationPolicy;
import monada.neuron.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicWorkloadGeneratorTest {

    @Test
    void generatesDeterministicFrequencyStatePairsFromSeed() {
        var gen1 = new DeterministicWorkloadGenerator(12345L);
        var gen2 = new DeterministicWorkloadGenerator(12345L);

        var pairs1 = gen1.generateFrequencyStatePairs(100);
        var pairs2 = gen2.generateFrequencyStatePairs(100);

        assertEquals(100, pairs1.size());
        assertEquals(pairs1, pairs2);
    }

    @Test
    void generatesDeterministicFrequencyStateBatchesFromSeed() {
        var gen1 = new DeterministicWorkloadGenerator(12345L);
        var gen2 = new DeterministicWorkloadGenerator(12345L);

        var batches1 = gen1.generateFrequencyStateBatches(100);
        var batches2 = gen2.generateFrequencyStateBatches(100);

        assertEquals(100, batches1.size());
        assertEquals(batches1.first(), batches2.first());
        assertEquals(batches1.second(), batches2.second());
    }

    @Test
    void generatesDeterministicSignals() {
        var gen1 = new DeterministicWorkloadGenerator(42L);
        var gen2 = new DeterministicWorkloadGenerator(42L);

        var sigs1 = gen1.generateSignals(50);
        var sigs2 = gen2.generateSignals(50);

        assertEquals(50, sigs1.size());
        assertEquals(sigs1, sigs2);
    }

    @Test
    void generatesDeterministicGraphTopologyWithSortedIds() {
        var generator = new DeterministicWorkloadGenerator(42L);
        var topology = generator.generateGraph(50, 4);

        assertAll(
                () -> assertEquals(50, topology.nodeCount()),
                () -> assertNotNull(topology.entryNode()),
                () -> assertTrue(topology.totalEdges() > 0));

        // Check node UUIDs are strictly sorted
        List<Node> nodes = topology.nodes();
        for (int i = 0; i < nodes.size() - 1; i++) {
            assertTrue(nodes.get(i).getId().compareTo(nodes.get(i + 1).getId()) < 0);
        }
    }

    @Test
    void generatesAeonAndFullCycleSetup() {
        var generator = new DeterministicWorkloadGenerator(42L);
        var perceptionTop = generator.generateGraph(20, 2);
        var reasoningTop = generator.generateGraph(20, 2);

        var aeon = generator.generateAeon(AeonPurpose.PERCEPTION, perceptionTop);
        assertEquals(20, aeon.getMembers().size());
        assertEquals(AeonPurpose.PERCEPTION, aeon.getPurpose());


        var setup = generator.generateFullCycleSetup(
                perceptionTop,
                reasoningTop,
                NoOpAdaptationPolicy.INSTANCE,
                new DeterministicMemoryFixture(),
                new DeterministicActionFixture(),
                DeterministicWorkloadGenerator.FULL_CYCLE_PROPAGATION);

        assertNotNull(setup.monad());
        assertNotNull(setup.cycle());
    }

    @Test
    void validatesInputArguments() {
        var generator = new DeterministicWorkloadGenerator();
        assertThrows(IllegalArgumentException.class, () -> generator.generateFrequencyStatePairs(0));
        assertThrows(IllegalArgumentException.class, () -> generator.generateSignals(-1));
        assertThrows(IllegalArgumentException.class, () -> generator.generateGraph(0, 0));
        assertThrows(IllegalArgumentException.class, () -> generator.generateGraph(10, 10));
    }

    @Test
    void generatesDeterministicHypothesisSetsAndScores() {
        var gen1 = new DeterministicWorkloadGenerator(7L);
        var gen2 = new DeterministicWorkloadGenerator(7L);

        var set = gen1.generateHypothesisSet(50, 4);
        var scores = gen1.generateHypothesisScores(50);

        assertAll(
                () -> assertEquals(50, set.size()),
                () -> assertTrue(set.hypotheses().stream().allMatch(h -> h.evidence().size() == 4)),
                () -> assertEquals(set, gen2.generateHypothesisSet(50, 4)),
                () -> assertArrayEquals(scores, gen2.generateHypothesisScores(50)),
                () -> assertTrue(Arrays.stream(scores).allMatch(score -> score >= 0.0 && score < 0.9)),
                () -> assertThrows(IllegalArgumentException.class, () -> gen1.generateHypothesisSet(0, 4)),
                () -> assertThrows(IllegalArgumentException.class, () -> gen1.generateHypothesisSet(4, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> gen1.generateHypothesisScores(0)));
    }

    @Test
    void keepsTheNodeObjectGraphModelUnchangedByTheBoundedHistory() {
        // 224 B per node: a 56 B Node, UUID, FrequencyState, the unmodifiable-set wrapper, and the empty
        // adjacency set; plus 32 B per directed edge. The lazily allocated history holder adds nothing to
        // the base, which a direct measurement of retained heap per node confirmed.
        assertAll(
                () -> assertEquals(0L, DeterministicWorkloadGenerator.estimateRetainedHeapBytes(0, 5)),
                () -> assertEquals(224L, DeterministicWorkloadGenerator.estimateRetainedHeapBytes(1, 0)),
                () -> assertEquals(10L * 224L + 3L * 32L, DeterministicWorkloadGenerator.estimateRetainedHeapBytes(10, 3)));
    }

    @Test
    void estimatesCompactSnapshotStorageSeparatelyFromTheSharedObjectGraph() {
        long empty = DeterministicWorkloadGenerator.estimateCompactSnapshotHeapBytes(0, 0);
        long small = DeterministicWorkloadGenerator.estimateCompactSnapshotHeapBytes(50, 143);
        long large = DeterministicWorkloadGenerator.estimateCompactSnapshotHeapBytes(2_000, 15_913);

        assertAll(
                () -> assertEquals(0L, empty),
                () -> assertTrue(small > 0L),
                () -> assertTrue(large > small),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DeterministicWorkloadGenerator.estimateCompactSnapshotHeapBytes(1, -1)));
    }
}
