package monada.neuron.evaluation.baseline;

import monada.neuron.evaluation.metrics.BenchmarkRunResult;
import monada.neuron.evaluation.metrics.EvaluationMetricsCollector;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.NodeStateLayoutWorkload;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.runtime.graph.CompactGraphSnapshot;
import monada.neuron.runtime.state.NodeStateSnapshot;
import monada.neuron.runtime.state.NodeStateStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * End-to-end comparison for the object state, heap SoA, and FFM state-layout experiment.
 *
 * <p>The experiment intentionally measures copied execution state. It does not route propagation
 * through FFM or synchronize snapshot updates back into Nodes.
 */
final class NodeStateLayoutExperiment {

    private static final long BYTES_PER_NODE = 4L * Double.BYTES;

    private final long seed;
    private final boolean quickMode;
    private final EvaluationMetricsCollector collector;

    NodeStateLayoutExperiment(long seed, boolean quickMode, EvaluationMetricsCollector collector) {
        this.seed = seed;
        this.quickMode = quickMode;
        this.collector = collector;
    }

    List<BenchmarkRunResult> run() {
        int nodeCount = quickMode ? 10_000 : 100_000;
        int randomReadCount = Math.min(nodeCount, 10_000);
        int updateCount = Math.min(nodeCount, 10_000);
        int warmups = quickMode ? 1 : 3;
        int iterations = quickMode ? 3 : 8;
        var fixture = NodeStateLayoutWorkload.create(seed ^ 0x53544154454CL, nodeCount, randomReadCount, updateCount);

        verifyEquivalence(fixture);

        var results = new ArrayList<BenchmarkRunResult>();
        results.addAll(measureConstruction(fixture, warmups, iterations));
        for (Backend backend : Backend.values()) {
            results.add(measureOperation(
                    fixture,
                    backend,
                    "SequentialRead",
                    nodeCount,
                    warmups,
                    iterations,
                    StateRun::sequentialRead));
            results.add(measureOperation(
                    fixture,
                    backend,
                    "RandomRead",
                    randomReadCount,
                    warmups,
                    iterations,
                    StateRun::randomRead));
            results.add(measureOperation(
                    fixture,
                    backend,
                    "BoundedUpdate",
                    updateCount,
                    warmups,
                    iterations,
                    StateRun::boundedUpdate));
        }
        results.addAll(measureCsrIntegration(fixture, warmups, iterations));
        return results;
    }

    private List<BenchmarkRunResult> measureConstruction(
            NodeStateLayoutWorkload.Fixture fixture,
            int warmups,
            int iterations) {
        int nodeCount = fixture.nodes().size();
        var results = new ArrayList<BenchmarkRunResult>();
        results.add(collector.measure(
                "NodeStateLayout.ObjectConstruction",
                scale(nodeCount),
                warmups,
                iterations,
                nodeCount,
                () -> {
                    Node[] nodes = NodeStateLayoutWorkload.freshNodes(fixture);
                    if (nodes.length != nodeCount) {
                        throw new IllegalStateException("object fixture population mismatch");
                    }
                },
                diagnostics(Backend.OBJECT, nodeCount, "construction")));

        results.add(measureSnapshotConstruction(fixture, Backend.HEAP, warmups, iterations, false));
        results.add(measureSnapshotConstruction(fixture, Backend.FFM, warmups, iterations, false));
        return results;
    }

    private List<BenchmarkRunResult> measureCsrIntegration(
            NodeStateLayoutWorkload.Fixture fixture,
            int warmups,
            int iterations) {
        return List.of(
                measureSnapshotConstruction(fixture, Backend.HEAP, warmups, iterations, true),
                measureSnapshotConstruction(fixture, Backend.FFM, warmups, iterations, true));
    }

    private BenchmarkRunResult measureSnapshotConstruction(
            NodeStateLayoutWorkload.Fixture fixture,
            Backend backend,
            int warmups,
            int iterations,
            boolean withCsr) {
        int nodeCount = fixture.nodes().size();
        String name = withCsr
                ? "NodeStateLayout.CsrStateSnapshotConstruction." + backend.label
                : "NodeStateLayout." + backend.label + "SnapshotConstruction";
        return collector.measure(
                name,
                scale(nodeCount),
                warmups,
                iterations,
                nodeCount,
                () -> {
                    CompactGraphSnapshot graphSnapshot = withCsr
                            ? CompactGraphSnapshot.compile(fixture.nodes())
                            : null;
                    try (NodeStateSnapshot stateSnapshot = compile(backend, fixture.nodes())) {
                        if (stateSnapshot.nodeCount() != nodeCount
                                || (graphSnapshot != null && graphSnapshot.nodeCount() != nodeCount)) {
                            throw new IllegalStateException("state snapshot population mismatch");
                        }
                    }
                },
                diagnostics(backend, nodeCount, withCsr ? "csr-and-state-compilation" : "state-compilation"));
    }

    private BenchmarkRunResult measureOperation(
            NodeStateLayoutWorkload.Fixture fixture,
            Backend backend,
            String operation,
            int operationsPerIteration,
            int warmups,
            int iterations,
            ToDoubleFunction<StateRun> measuredOperation) {
        StateRun[] holder = new StateRun[1];
        Runnable setup = () -> {
            closeQuietly(holder[0]);
            holder[0] = StateRun.create(backend, fixture);
        };
        try {
            return collector.measure(
                    "NodeStateLayout." + backend.label + "." + operation,
                    scale(fixture.nodes().size()),
                    warmups,
                    iterations,
                    operationsPerIteration,
                    setup,
                    () -> {
                        double checksum = measuredOperation.applyAsDouble(holder[0]);
                        if (!Double.isFinite(checksum)) {
                            throw new IllegalStateException("state-layout checksum must remain finite");
                        }
                    },
                    diagnostics(backend, fixture.nodes().size(), operation));
        } finally {
            closeQuietly(holder[0]);
        }
    }

    private void verifyEquivalence(NodeStateLayoutWorkload.Fixture fixture) {
        try (var objectRun = StateRun.create(Backend.OBJECT, fixture);
                var heapRun = StateRun.create(Backend.HEAP, fixture);
                var ffmRun = StateRun.create(Backend.FFM, fixture)) {
            requireEqual("initial sequential reads", objectRun.sequentialRead(), heapRun.sequentialRead(), ffmRun.sequentialRead());
            requireEqual("initial random reads", objectRun.randomRead(), heapRun.randomRead(), ffmRun.randomRead());
            requireEqual("bounded updates", objectRun.boundedUpdate(), heapRun.boundedUpdate(), ffmRun.boundedUpdate());
            requireEqual("updated sequential reads", objectRun.sequentialRead(), heapRun.sequentialRead(), ffmRun.sequentialRead());
        }
    }

    private NodeStateSnapshot compile(Backend backend, List<Node> nodes) {
        return switch (backend) {
            case HEAP -> NodeStateSnapshot.compileOnHeap(nodes);
            case FFM -> NodeStateSnapshot.compileOffHeap(nodes);
            case OBJECT -> throw new IllegalArgumentException("object baseline has no copied state snapshot");
        };
    }

    private Map<String, String> diagnostics(Backend backend, int nodeCount, String operation) {
        long logicalBytes = Math.multiplyExact((long) nodeCount, BYTES_PER_NODE);
        return Map.of(
                "backend", backend.label,
                "operation", operation,
                "nodeCount", String.valueOf(nodeCount),
                "logicalStateBytes", String.valueOf(logicalBytes),
                "offHeapCommittedBytes", String.valueOf(backend == Backend.FFM ? logicalBytes : 0L),
                "objectTopologyEstimatedRetainedBytes", String.valueOf(
                        DeterministicWorkloadGenerator.estimateRetainedHeapBytes(nodeCount, 0)),
                "stateSynchronization", "copied snapshot; no Node write-back",
                "concurrency", "single-threaded" );
    }

    private String scale(int nodeCount) {
        return nodeCount + " nodes, 4 double state channels";
    }

    private void requireEqual(String description, double first, double second, double third) {
        if (Double.doubleToLongBits(first) != Double.doubleToLongBits(second)
                || Double.doubleToLongBits(first) != Double.doubleToLongBits(third)) {
            throw new IllegalStateException(description + " diverged between object, heap, and FFM layouts");
        }
    }

    private void closeQuietly(StateRun run) {
        if (run != null) {
            run.close();
        }
    }

    private enum Backend {
        OBJECT("Object"),
        HEAP("HeapSoA"),
        FFM("Ffm");

        private final String label;

        Backend(String label) {
            this.label = label;
        }
    }

    private static final class StateRun implements AutoCloseable {

        private final Backend backend;
        private final NodeStateLayoutWorkload.Fixture fixture;
        private final Node[] nodes;
        private final NodeStateSnapshot snapshot;

        private StateRun(
                Backend backend,
                NodeStateLayoutWorkload.Fixture fixture,
                Node[] nodes,
                NodeStateSnapshot snapshot) {
            this.backend = backend;
            this.fixture = fixture;
            this.nodes = nodes;
            this.snapshot = snapshot;
        }

        static StateRun create(Backend backend, NodeStateLayoutWorkload.Fixture fixture) {
            Node[] nodes = NodeStateLayoutWorkload.freshNodes(fixture);
            NodeStateSnapshot snapshot = switch (backend) {
                case OBJECT -> null;
                case HEAP -> NodeStateSnapshot.compileOnHeap(Arrays.asList(nodes));
                case FFM -> NodeStateSnapshot.compileOffHeap(Arrays.asList(nodes));
            };
            return new StateRun(backend, fixture, nodes, snapshot);
        }

        double sequentialRead() {
            double checksum = 0.0;
            if (backend == Backend.OBJECT) {
                for (Node node : nodes) {
                    var state = node.getFrequencyState();
                    checksum += state.amplitude() + state.frequency() + state.phase() + node.getEnergy();
                }
                return checksum;
            }
            NodeStateStore store = snapshot.stateStore();
            for (int index = 0; index < nodes.length; index++) {
                checksum += store.amplitudeAt(index)
                        + store.frequencyAt(index)
                        + store.phaseAt(index)
                        + store.energyAt(index);
            }
            return checksum;
        }

        double randomRead() {
            double checksum = 0.0;
            if (backend == Backend.OBJECT) {
                for (int index : fixture.randomIndices()) {
                    Node node = nodes[index];
                    var state = node.getFrequencyState();
                    checksum += state.amplitude() + state.frequency() + state.phase() + node.getEnergy();
                }
                return checksum;
            }
            NodeStateStore store = snapshot.stateStore();
            for (int index : fixture.randomIndices()) {
                checksum += store.amplitudeAt(index)
                        + store.frequencyAt(index)
                        + store.phaseAt(index)
                        + store.energyAt(index);
            }
            return checksum;
        }

        double boundedUpdate() {
            double checksum = 0.0;
            for (int update = 0; update < fixture.updateStates().length; update++) {
                int index = update % nodes.length;
                FrequencyState state = fixture.updateStates()[update];
                double energy = fixture.updateEnergies()[update];
                if (backend == Backend.OBJECT) {
                    nodes[index].transition(state);
                    nodes[index].setEnergy(energy);
                    checksum += nodes[index].getFrequencyState().amplitude() + nodes[index].getEnergy();
                } else {
                    NodeStateStore store = snapshot.stateStore();
                    store.setState(index, state, energy);
                    checksum += store.amplitudeAt(index) + store.energyAt(index);
                }
            }
            return checksum;
        }

        @Override
        public void close() {
            if (snapshot != null) {
                snapshot.close();
            }
        }
    }
}
