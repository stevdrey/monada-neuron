package monada.neuron.evaluation.workload;

import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/** Deterministic fixtures shared by the object, heap-SoA, and FFM state-layout experiments. */
public final class NodeStateLayoutWorkload {

    private NodeStateLayoutWorkload() {
    }

    /** Builds one stable, zero-edge population and deterministic random/read-update schedules. */
    public static Fixture create(long seed, int nodeCount, int randomReadCount, int updateCount) {
        requirePositive("nodeCount", nodeCount);
        requirePositive("randomReadCount", randomReadCount);
        requirePositive("updateCount", updateCount);

        var random = new Random(seed);
        var types = NodeType.values();
        var nodes = new ArrayList<Node>(nodeCount);
        for (int index = 0; index < nodeCount; index++) {
            nodes.add(new Node.Builder()
                    .id(new UUID(seed ^ 0x4E4F4445L, index))
                    .type(types[index % types.length])
                    .frequencyState(nextState(random))
                    .energy(0.1 + random.nextDouble() * 0.9)
                    .build());
        }

        int[] randomIndices = new int[randomReadCount];
        FrequencyState[] updateStates = new FrequencyState[updateCount];
        double[] updateEnergies = new double[updateCount];
        for (int index = 0; index < randomReadCount; index++) {
            randomIndices[index] = random.nextInt(nodeCount);
        }
        for (int index = 0; index < updateCount; index++) {
            updateStates[index] = nextState(random);
            updateEnergies[index] = random.nextDouble();
        }

        return new Fixture(List.copyOf(nodes), randomIndices, updateStates, updateEnergies);
    }

    /** Recreates Nodes from the fixture so mutation benchmarks begin without retained history. */
    public static Node[] freshNodes(Fixture fixture) {
        Objects.requireNonNull(fixture, "fixture must not be null");
        Node[] copies = new Node[fixture.nodes().size()];
        for (int index = 0; index < copies.length; index++) {
            Node source = fixture.nodes().get(index);
            copies[index] = new Node.Builder()
                    .id(source.getId())
                    .type(source.getType())
                    .frequencyState(source.getFrequencyState())
                    .energy(source.getEnergy())
                    .build();
        }
        return copies;
    }

    private static FrequencyState nextState(Random random) {
        return new FrequencyState(
                0.05 + random.nextDouble() * 0.95,
                random.nextDouble() * 100.0,
                random.nextDouble() * 2.0 * StrictMath.PI);
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got: " + value);
        }
    }

    /** Immutable fixture inputs; arrays are owned by evaluation code and never mutated. */
    public record Fixture(
            List<Node> nodes,
            int[] randomIndices,
            FrequencyState[] updateStates,
            double[] updateEnergies) {

        public Fixture {
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
            Objects.requireNonNull(randomIndices, "randomIndices must not be null");
            Objects.requireNonNull(updateStates, "updateStates must not be null");
            Objects.requireNonNull(updateEnergies, "updateEnergies must not be null");
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("nodes must not be empty");
            }
            if (updateStates.length != updateEnergies.length) {
                throw new IllegalArgumentException("update state and energy schedules must have equal length");
            }
        }
    }
}
