package monada.neuron.evaluation.jmh;

import monada.neuron.evaluation.workload.NodeStateLayoutWorkload;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.runtime.state.NodeStateSnapshot;
import monada.neuron.runtime.state.NodeStateStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * JMH comparison for construction and steady-state access to selected large-node state layouts.
 *
 * <p>Each iteration starts from fresh Nodes and copied layouts. This prevents the object baseline's
 * intentional history from accumulating across a long update measurement while keeping setup out
 * of the timed benchmark operation.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class NodeStateLayoutBenchmark {

    @Param({"100000", "1000000"})
    private int nodeCount;

    private NodeStateLayoutWorkload.Fixture fixture;
    private Node[] objectNodes;
    private NodeStateSnapshot heapSnapshot;
    private NodeStateSnapshot ffmSnapshot;

    @Setup(Level.Trial)
    public void createFixture() {
        fixture = NodeStateLayoutWorkload.create(
                42L,
                nodeCount,
                Math.min(nodeCount, 100_000),
                Math.min(nodeCount, 256));
    }

    @Setup(Level.Iteration)
    public void resetState() {
        closeSnapshots();
        objectNodes = NodeStateLayoutWorkload.freshNodes(fixture);
        heapSnapshot = NodeStateSnapshot.compileOnHeap(Arrays.asList(objectNodes));
        ffmSnapshot = NodeStateSnapshot.compileOffHeap(Arrays.asList(objectNodes));
    }

    @Setup(Level.Invocation)
    public void resetMutatedNodes() {
        if (fixture == null || objectNodes == null) {
            return;
        }
        int updateCount = fixture.updateStates().length;
        for (int i = 0; i < updateCount; i++) {
            int index = i % objectNodes.length;
            Node source = fixture.nodes().get(index);
            objectNodes[index] = new Node.Builder()
                    .id(source.getId())
                    .type(source.getType())
                    .frequencyState(source.getFrequencyState())
                    .energy(source.getEnergy())
                    .build();
        }
    }

    @TearDown(Level.Iteration)
    public void releaseState() {
        closeSnapshots();
    }

    @Benchmark
    public double benchmarkObjectConstruction() {
        Node[] nodes = NodeStateLayoutWorkload.freshNodes(fixture);
        Node last = nodes[nodes.length - 1];
        return nodes.length + last.getFrequencyState().frequency() + last.getEnergy();
    }

    @Benchmark
    public double benchmarkHeapSnapshotConstruction() {
        try (var snapshot = NodeStateSnapshot.compileOnHeap(fixture.nodes())) {
            NodeStateStore store = snapshot.stateStore();
            return snapshot.nodeCount() + store.frequencyAt(snapshot.nodeCount() - 1);
        }
    }

    @Benchmark
    public double benchmarkFfmSnapshotConstruction() {
        try (var snapshot = NodeStateSnapshot.compileOffHeap(fixture.nodes())) {
            NodeStateStore store = snapshot.stateStore();
            return snapshot.nodeCount() + store.frequencyAt(snapshot.nodeCount() - 1);
        }
    }

    @Benchmark
    public double benchmarkObjectSequentialRead() {
        double checksum = 0.0;
        for (Node node : objectNodes) {
            var state = node.getFrequencyState();
            checksum += state.amplitude() + state.frequency() + state.phase() + node.getEnergy();
        }
        return checksum;
    }

    @Benchmark
    public double benchmarkHeapSequentialRead() {
        return sequentialRead(heapSnapshot.stateStore());
    }

    @Benchmark
    public double benchmarkFfmSequentialRead() {
        return sequentialRead(ffmSnapshot.stateStore());
    }

    @Benchmark
    public double benchmarkObjectRandomRead() {
        double checksum = 0.0;
        for (int index : fixture.randomIndices()) {
            Node node = objectNodes[index];
            var state = node.getFrequencyState();
            checksum += state.amplitude() + state.frequency() + state.phase() + node.getEnergy();
        }
        return checksum;
    }

    @Benchmark
    public double benchmarkHeapRandomRead() {
        return randomRead(heapSnapshot.stateStore());
    }

    @Benchmark
    public double benchmarkFfmRandomRead() {
        return randomRead(ffmSnapshot.stateStore());
    }

    /**
     * Benchmarks bounded state updates using domain {@link Node#transition(FrequencyState)} semantics.
     *
     * <p>Each invocation updates target nodes reset to fresh state, capturing exactly one history
     * transition per node and updating its energy level.
     */
    @Benchmark
    public double benchmarkObjectBoundedUpdate() {
        double checksum = 0.0;
        for (int update = 0; update < fixture.updateStates().length; update++) {
            int index = update % objectNodes.length;
            FrequencyState state = fixture.updateStates()[update];
            objectNodes[index].transition(state);
            objectNodes[index].setEnergy(fixture.updateEnergies()[update]);
            checksum += objectNodes[index].getFrequencyState().amplitude() + objectNodes[index].getEnergy();
        }
        return checksum;
    }

    /**
     * Benchmarks bounded physical in-place Structure-of-Arrays updates on {@link HeapNodeStateStore}.
     *
     * <p>Overwrites the 4 primitive channels without recording history.
     */
    @Benchmark
    public double benchmarkHeapBoundedUpdate() {
        return boundedUpdate(heapSnapshot.stateStore());
    }

    /**
     * Benchmarks bounded physical in-place Structure-of-Arrays updates on {@link FfmNodeStateStore}.
     *
     * <p>Overwrites the 4 off-heap native channels without recording history.
     */
    @Benchmark
    public double benchmarkFfmBoundedUpdate() {
        return boundedUpdate(ffmSnapshot.stateStore());
    }

    private double sequentialRead(NodeStateStore store) {
        double checksum = 0.0;
        for (int index = 0; index < nodeCount; index++) {
            checksum += store.amplitudeAt(index)
                    + store.frequencyAt(index)
                    + store.phaseAt(index)
                    + store.energyAt(index);
        }
        return checksum;
    }

    private double randomRead(NodeStateStore store) {
        double checksum = 0.0;
        for (int index : fixture.randomIndices()) {
            checksum += store.amplitudeAt(index)
                    + store.frequencyAt(index)
                    + store.phaseAt(index)
                    + store.energyAt(index);
        }
        return checksum;
    }

    private double boundedUpdate(NodeStateStore store) {
        double checksum = 0.0;
        for (int update = 0; update < fixture.updateStates().length; update++) {
            int index = update % nodeCount;
            FrequencyState state = fixture.updateStates()[update];
            double energy = fixture.updateEnergies()[update];
            store.setState(index, state, energy);
            checksum += store.amplitudeAt(index) + store.energyAt(index);
        }
        return checksum;
    }

    private void closeSnapshots() {
        if (heapSnapshot != null) {
            heapSnapshot.close();
            heapSnapshot = null;
        }
        if (ffmSnapshot != null) {
            ffmSnapshot.close();
            ffmSnapshot = null;
        }
    }
}
