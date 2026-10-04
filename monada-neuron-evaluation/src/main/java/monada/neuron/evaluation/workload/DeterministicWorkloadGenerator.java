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
import monada.neuron.reasoning.EvidenceRelation;
import monada.neuron.reasoning.HypothesisLimits;
import monada.neuron.reasoning.HypothesisSet;
import monada.neuron.reasoning.HypothesisSetBuilder;
import monada.neuron.reasoning.Proposition;
import monada.neuron.reasoning.SignalEvidence;
import monada.neuron.resonance.FrequencyStateBatch;
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
     * Generates a deterministic hypothesis set with a mixed supporting, contradicting, and neutral
     * evidence profile. Weights come from a coarse grid so equal scores occur realistically.
     *
     * @param candidateCount number of candidate hypotheses
     * @param evidencePerCandidate evidence entries attached to each candidate
     * @return an immutable hypothesis set sized exactly to the request
     */
    public HypothesisSet generateHypothesisSet(int candidateCount, int evidencePerCandidate) {
        if (candidateCount <= 0) {
            throw new IllegalArgumentException("candidateCount must be positive, got: " + candidateCount);
        }
        if (evidencePerCandidate <= 0) {
            throw new IllegalArgumentException(
                    "evidencePerCandidate must be positive, got: " + evidencePerCandidate);
        }
        var random = new Random(seed ^ 0x48595054L);
        var builder = new HypothesisSetBuilder(new HypothesisLimits(candidateCount, evidencePerCandidate));
        for (var i = 0; i < candidateCount; i++) {
            var sequence = builder.propose(new Proposition(0, i)).getAsInt();
            for (var e = 0; e < evidencePerCandidate; e++) {
                var roll = random.nextInt(100);
                var relation = roll < 60
                        ? EvidenceRelation.SUPPORTS
                        : roll < 85 ? EvidenceRelation.CONTRADICTS : EvidenceRelation.NEUTRAL;
                builder.addEvidence(sequence, new SignalEvidence(e, relation, (1 + random.nextInt(8)) / 8.0));
            }
        }
        return builder.build();
    }

    /**
     * Generates deterministic candidate scores in {@code [0, 0.9)} on a coarse grid, for isolating
     * top-K selection cost from scoring cost.
     *
     * @param candidateCount number of scores
     * @return a new score array
     */
    public double[] generateHypothesisScores(int candidateCount) {
        if (candidateCount <= 0) {
            throw new IllegalArgumentException("candidateCount must be positive, got: " + candidateCount);
        }
        var random = new Random(seed ^ 0x53434f52L);
        var scores = new double[candidateCount];
        for (var i = 0; i < candidateCount; i++) {
            scores[i] = random.nextInt(64) / 64.0 * 0.9;
        }
        return scores;
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
     * Generates a pair of contiguous {@link FrequencyStateBatch} instances for batch resonance benchmarking.
     *
     * @param pairCount number of pairs to generate
     * @return record containing the first and second contiguous batches
     */
    public FrequencyStateBatchPair generateFrequencyStateBatches(int pairCount) {
        if (pairCount <= 0) {
            throw new IllegalArgumentException("pairCount must be positive, got: " + pairCount);
        }
        var random = new Random(seed);
        double[] a1 = new double[pairCount];
        double[] f1 = new double[pairCount];
        double[] p1 = new double[pairCount];
        double[] a2 = new double[pairCount];
        double[] f2 = new double[pairCount];
        double[] p2 = new double[pairCount];

        for (int i = 0; i < pairCount; i++) {
            a1[i] = 0.05 + random.nextDouble() * 0.95;
            f1[i] = random.nextDouble() * 100.0;
            p1[i] = random.nextDouble() * 2.0 * StrictMath.PI;

            a2[i] = 0.05 + random.nextDouble() * 0.95;
            f2[i] = random.nextDouble() * 100.0;
            p2[i] = random.nextDouble() * 2.0 * StrictMath.PI;
        }

        var first = FrequencyStateBatch.of(a1, f1, p1);
        var second = FrequencyStateBatch.of(a2, f2, p2);
        return new FrequencyStateBatchPair(first, second);
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
     * Estimates the retained heap footprint in bytes for a Monada Neuron Node object graph.
     *
     * <p>Footprint model on 64-bit HotSpot JVM with Compressed OOPs (-XX:+UseCompressedOops):
     * <ul>
     *   <li>{@link Node} instance: 64 bytes (header + six references + primitive fields, including
     *       the topology-version counter and the three {@code int} fields of the bounded history ring
     *       buffer: head, size, and limit). The history array itself is allocated lazily and is not
     *       part of this base; once materialized it adds at most {@code 16 + 4 * limit} bytes plus the
     *       retained {@link FrequencyState} values.</li>
     *   <li>{@link UUID}: 32 bytes (header + two 64-bit longs).</li>
     *   <li>{@link FrequencyState}: 32 bytes (header + three 64-bit doubles).</li>
     *   <li>{@link java.util.Collections#unmodifiableSet}: 24 bytes wrapper.</li>
     *   <li>{@link java.util.HashSet} and backing {@link java.util.HashMap}: ~80 bytes base + table array (~4 bytes/entry).</li>
     *   <li>Adjacency entries: 32 bytes per {@code HashMap$Node} edge entry.</li>
     * </ul>
     * Total per node base: ~232 bytes. Total per directed edge: ~32 bytes.
     *
     * <p>The model assumes classic 12-byte object headers ({@code -XX:-UseCompactObjectHeaders}). JDK 27
     * enables compact 8-byte headers by default, where real nodes are smaller. A direct measurement of
     * retained heap per node without edges (400,000 nodes, serial GC) gave 218.8 B on the previous layout
     * and 226.9 B with the ring-buffer fields (+8.1 B) under classic headers, and 186.8 B and 203.3 B
     * (+16.5 B) under JDK 27's default compact headers. The per-component sizes above are an approximation
     * and were not each re-measured; the sum is the figure that tracks the measurement.
     *
     * @param nodeCount number of nodes in the graph
     * @param totalEdges total directed edges across all nodes
     * @return approximate retained heap footprint in bytes
     */
    public static long estimateRetainedHeapBytes(int nodeCount, int totalEdges) {
        if (nodeCount <= 0) {
            return 0L;
        }
        return ((long) nodeCount * 232L) + ((long) totalEdges * 32L);
    }

    /**
     * Estimates the incremental retained heap of one compact CSR execution snapshot.
     *
     * <p>The estimate models the snapshot object, its canonical {@code Node[]} references,
     * topology-version {@code long[]}, CSR {@code offsets} and {@code targets} arrays on a
     * 64-bit HotSpot JVM with compressed references. It deliberately excludes the shared Node
     * object graph, which remains live because a compact snapshot is a runtime view rather than a
     * domain-model replacement.
     *
     * @param nodeCount number of canonical Nodes
     * @param totalEdges total directed CSR entries
     * @return approximate incremental bytes retained by the compact snapshot
     */
    public static long estimateCompactSnapshotHeapBytes(int nodeCount, int totalEdges) {
        if (nodeCount <= 0) {
            return 0L;
        }
        if (totalEdges < 0) {
            throw new IllegalArgumentException("totalEdges must be non-negative, got: " + totalEdges);
        }
        long snapshotObject = 32L;
        long nodeReferences = alignedArrayBytes(Integer.BYTES, nodeCount);
        long topologyVersions = alignedArrayBytes(Long.BYTES, nodeCount);
        long offsets = alignedArrayBytes(Integer.BYTES, nodeCount + 1L);
        long targets = alignedArrayBytes(Integer.BYTES, totalEdges);
        return snapshotObject + nodeReferences + topologyVersions + offsets + targets;
    }

    private static long alignedArrayBytes(int elementBytes, long length) {
        long rawBytes;
        try {
            rawBytes = Math.addExact(16L, Math.multiplyExact(elementBytes, length));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("array size exceeds supported footprint estimate", exception);
        }
        return (rawBytes + 7L) & ~7L;
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
        return generateFullCycleSetup(
                perceptionTopology,
                reasoningTopology,
                policy,
                memoryPort,
                actionCapability,
                PropagationConfig.routeAll(50, 4));
    }

    /**
     * Generates a full cognitive cycle with an explicit per-Aeon propagation bound.
     *
     * <p>The default bound (50 steps, 4 hops) truncates the perception stage on the standard
     * benchmark topologies, which ends the cycle before memory recall; callers that must reach later
     * stages pass a larger bound.
     *
     * @param propagationConfig propagation limits shared by both Aeon stages
     */
    public CognitiveCycleSetup generateFullCycleSetup(
            GraphTopology perceptionTopology,
            GraphTopology reasoningTopology,
            AdaptationPolicy policy,
            ResonanceMemoryPort memoryPort,
            ActionCapability actionCapability,
            PropagationConfig propagationConfig) {
        Objects.requireNonNull(propagationConfig, "propagationConfig must not be null");
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

    /** Pair of contiguous frequency state batches for SIMD batch resonance evaluation. */
    public record FrequencyStateBatchPair(FrequencyStateBatch first, FrequencyStateBatch second) {
        public FrequencyStateBatchPair {
            Objects.requireNonNull(first, "first must not be null");
            Objects.requireNonNull(second, "second must not be null");
            if (first.size() != second.size()) {
                throw new IllegalArgumentException("batches must have equal sizes");
            }
        }

        public int size() {
            return first.size();
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
