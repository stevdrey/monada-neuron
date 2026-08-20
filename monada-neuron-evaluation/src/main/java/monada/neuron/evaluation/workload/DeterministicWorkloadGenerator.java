package monada.neuron.evaluation.workload;

import monada.neuron.action.ActionCapability;
import monada.neuron.action.ActionCognitiveStage;
import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.aeon.CognitiveAeonCoordinator;
import monada.neuron.aeon.DeterministicAeonCoordinator;
import monada.neuron.evolution.AdaptationCognitiveStage;
import monada.neuron.evolution.AdaptationPolicy;
import monada.neuron.memory.ResonanceMemoryCognitiveStage;
import monada.neuron.memory.ResonanceMemoryPort;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.model.NodeType;
import monada.neuron.monad.AeonCognitiveStage;
import monada.neuron.monad.CognitiveCycle;
import monada.neuron.monad.CognitiveStage;
import monada.neuron.monad.CognitiveStageKind;
import monada.neuron.monad.DeterministicCognitiveCycle;
import monada.neuron.monad.PrimaryMonad;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * Deterministic synthetic generator for representative Monada Neuron evaluation workloads.
 *
 * <p>Produces reproducible sequences of frequency states, signals, sparse directed node graphs,
 * Aeons, and cognitive cycle stage plans from fixed seeds.
 */
public final class DeterministicWorkloadGenerator {

    /** Default seed used for reproducible baseline benchmarks. */
    public static final long DEFAULT_SEED = 42L;

    private final long seed;

    /**
     * Creates a workload generator with the default seed.
     */
    public DeterministicWorkloadGenerator() {
        this(DEFAULT_SEED);
    }

    /**
     * Creates a workload generator with an explicit seed.
     *
     * @param seed initial deterministic seed
     */
    public DeterministicWorkloadGenerator(long seed) {
        this.seed = seed;
    }

    /** Returns the seed used by this generator. */
    public long seed() {
        return seed;
    }

    /**
     * Generates a batch of deterministic {@link FrequencyState} pairs for scalar resonance benchmarking.
     *
     * @param pairCount number of pairs to generate
     * @return an immutable list of frequency state pairs
     */
    public List<FrequencyStatePair> generateFrequencyStatePairs(int pairCount) {
        if (pairCount <= 0) {
            throw new IllegalArgumentException("pairCount must be positive, got: " + pairCount);
        }
        var random = new Random(seed);
        var pairs = new ArrayList<FrequencyStatePair>(pairCount);
        for (int i = 0; i < pairCount; i++) {
            var first = nextFrequencyState(random);
            var second = nextFrequencyState(random);
            pairs.add(new FrequencyStatePair(first, second));
        }
        return List.copyOf(pairs);
    }

    /**
     * Generates a batch of deterministic {@link Signal} values.
     *
     * @param count number of signals to generate
     * @return an immutable list of signals
     */
    public List<Signal> generateSignals(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive, got: " + count);
        }
        var random = new Random(seed ^ 0x5DEECE66DL);
        var kinds = SignalKind.values();
        var signals = new ArrayList<Signal>(count);
        for (int i = 0; i < count; i++) {
            var kind = kinds[random.nextInt(kinds.length)];
            var state = nextFrequencyState(random);
            signals.add(new Signal(kind, state));
        }
        return List.copyOf(signals);
    }

    /**
     * Generates a deterministic sparse directed graph topology.
     *
     * @param nodeCount number of nodes in the graph
     * @param avgDegree approximate average out-degree per node
     * @return the generated graph topology
     */
    public GraphTopology generateGraph(int nodeCount, int avgDegree) {
        if (nodeCount <= 0) {
            throw new IllegalArgumentException("nodeCount must be positive, got: " + nodeCount);
        }
        if (avgDegree < 0 || avgDegree >= nodeCount) {
            throw new IllegalArgumentException("avgDegree must be in [0, nodeCount - 1], got: " + avgDegree);
        }
        var random = new Random(seed ^ ((long) nodeCount * 31L + avgDegree));
        var nodeTypes = NodeType.values();

        var nodes = new ArrayList<Node>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            var uuid = new UUID(seed ^ 0xABCD1234L, (long) i);
            var type = nodeTypes[random.nextInt(nodeTypes.length)];
            var state = nextFrequencyState(random);
            double energy = 0.1 + random.nextDouble() * 0.9;
            var node = new Node.Builder()
                    .id(uuid)
                    .type(type)
                    .frequencyState(state)
                    .energy(energy)
                    .build();
            nodes.add(node);
        }

        // Sort nodes by UUID to ensure canonical ordering
        nodes.sort(Comparator.comparing(Node::getId));

        int totalEdges = 0;
        if (avgDegree > 0) {
            for (int i = 0; i < nodeCount; i++) {
                var source = nodes.get(i);
                // Connect to next avgDegree nodes in ring / pseudo-random fashion
                for (int d = 1; d <= avgDegree; d++) {
                    int targetIndex = (i + d + random.nextInt(Math.max(1, nodeCount / 4))) % nodeCount;
                    if (targetIndex != i) {
                        var target = nodes.get(targetIndex);
                        if (source.connect(target)) {
                            totalEdges++;
                        }
                    }
                }
            }
        }

        return new GraphTopology(List.copyOf(nodes), nodes.getFirst(), totalEdges);
    }

    /**
     * Creates a representative deterministic Aeon with connected members.
     *
     * @param purpose Aeon cognitive purpose
     * @param topology underlying graph topology
     * @return the populated Aeon
     */
    public Aeon generateAeon(AeonPurpose purpose, GraphTopology topology) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(topology, "topology must not be null");

        var aeonId = new UUID(seed ^ (long) purpose.name().hashCode(), 1L);
        var aeon = new Aeon(aeonId, purpose);
        for (var node : topology.nodes()) {
            aeon.addMember(node);
        }
        return aeon;
    }

    /**
     * Builds a representative 5-stage Primary Monad cognitive cycle with deterministic fixtures.
     *
     * @param perceptionTopology topology for perception stage
     * @param reasoningTopology topology for reasoning stage
     * @param policy adaptation policy (e.g. baseline or no-op)
     * @param memoryPort deterministic memory fixture
     * @param actionCapability deterministic action fixture
     * @return a prepared Monad and cycle setup
     */
    public CognitiveCycleSetup generateFullCycleSetup(

            GraphTopology perceptionTopology,
            GraphTopology reasoningTopology,
            AdaptationPolicy policy,
            ResonanceMemoryPort memoryPort,
            ActionCapability actionCapability) {
        Objects.requireNonNull(perceptionTopology, "perceptionTopology must not be null");
        Objects.requireNonNull(reasoningTopology, "reasoningTopology must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(memoryPort, "memoryPort must not be null");
        Objects.requireNonNull(actionCapability, "actionCapability must not be null");

        var perceptionAeon = generateAeon(AeonPurpose.PERCEPTION, perceptionTopology);
        var reasoningAeon = generateAeon(AeonPurpose.REASONING, reasoningTopology);

        var monadId = new UUID(seed, 0xCAFEBABEL);
        var monad = new PrimaryMonad(monadId);

        monad.registerAeon(perceptionAeon);
        monad.registerAeon(reasoningAeon);

        var coordinator = new DeterministicAeonCoordinator(new DeterministicSignalPropagationEngine());
        var propagationConfig = PropagationConfig.routeAll(50, 4);

        NodeProcessor forwardingProcessor = (node, input) -> {
            var transformed = new Signal(
                    SignalKind.INTERMEDIATE,
                    new FrequencyState(
                            Math.min(1.0, input.frequencyState().amplitude() * 0.9 + 0.1),
                            input.frequencyState().frequency(),
                            input.frequencyState().phase() + 0.1));
            return new NodeProcessingResult(List.of(transformed));
        };

        var perceptionStage = new AeonCognitiveStage(
                CognitiveStageKind.PERCEPTION,
                perceptionAeon,
                perceptionTopology.entryNode().getId(),
                coordinator,
                forwardingProcessor,
                propagationConfig);

        var memoryStage = new ResonanceMemoryCognitiveStage(memoryPort, 5);

        var reasoningStage = new AeonCognitiveStage(
                CognitiveStageKind.REASONING,
                reasoningAeon,
                reasoningTopology.entryNode().getId(),
                coordinator,
                forwardingProcessor,
                propagationConfig);

        var adaptationStage = new AdaptationCognitiveStage(policy, reasoningTopology.nodes());

        var actionStage = new ActionCognitiveStage(actionCapability, 5);

        var stages = List.<CognitiveStage>of(
                perceptionStage,
                memoryStage,
                reasoningStage,
                adaptationStage,
                actionStage);

        var cycle = new DeterministicCognitiveCycle(stages);
        return new CognitiveCycleSetup(monad, cycle, perceptionTopology, reasoningTopology);
    }

    private FrequencyState nextFrequencyState(Random random) {
        double amplitude = 0.05 + random.nextDouble() * 0.95;
        double frequency = random.nextDouble() * 100.0;
        double phase = random.nextDouble() * 2.0 * StrictMath.PI;
        return new FrequencyState(amplitude, frequency, phase);
    }

    /** Pair of frequency states for scalar resonance evaluation. */
    public record FrequencyStatePair(FrequencyState first, FrequencyState second) {
        public FrequencyStatePair {
            Objects.requireNonNull(first, "first must not be null");
            Objects.requireNonNull(second, "second must not be null");
        }
    }

    /** Deterministic graph topology representation. */
    public record GraphTopology(List<Node> nodes, Node entryNode, int totalEdges) {
        public GraphTopology {
            Objects.requireNonNull(nodes, "nodes must not be null");
            Objects.requireNonNull(entryNode, "entryNode must not be null");
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("nodes must not be empty");
            }
        }

        public int nodeCount() {
            return nodes.size();
        }
    }

    /** Setup record for an executable cognitive cycle. */
    public record CognitiveCycleSetup(
            PrimaryMonad monad,
            CognitiveCycle cycle,
            GraphTopology perceptionTopology,
            GraphTopology reasoningTopology) {
        public CognitiveCycleSetup {
            Objects.requireNonNull(monad, "monad must not be null");
            Objects.requireNonNull(cycle, "cycle must not be null");
            Objects.requireNonNull(perceptionTopology, "perceptionTopology must not be null");
            Objects.requireNonNull(reasoningTopology, "reasoningTopology must not be null");
        }
    }
}
