package monada.neuron.evaluation.jmh;

import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.GraphTopology;
import monada.neuron.model.Node;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.runtime.graph.CompactGraphSnapshot;
import monada.neuron.runtime.graph.CompactSignalPropagationEngine;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
import monada.neuron.runtime.graph.PropagationResult;
import monada.neuron.runtime.graph.ResonanceThresholdRoutingPolicy;
import monada.neuron.runtime.graph.SignalRoutingPolicy;
import monada.neuron.signal.NodeProcessingResult;
import monada.neuron.signal.NodeProcessor;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH comparison of object-graph propagation, CSR compilation, and steady-state CSR traversal.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class SignalPropagationBenchmark {

    @Param({"50", "500", "2000"})
    private int nodeCount;

    @Param({"RouteAll", "Threshold"})
    private String routingPolicyType;

    private DeterministicSignalPropagationEngine referenceEngine;
    private CompactSignalPropagationEngine compactEngine;
    private GraphTopology topology;
    private Signal initialSignal;
    private NodeProcessor processor;
    private PropagationConfig config;

    @Setup(Level.Trial)
    public void setup() {
        referenceEngine = new DeterministicSignalPropagationEngine();
        var generator = new DeterministicWorkloadGenerator();
        int degree = nodeCount == 50 ? 3 : nodeCount == 500 ? 5 : 8;
        topology = generator.generateGraph(nodeCount, degree);
        compactEngine = new CompactSignalPropagationEngine(CompactGraphSnapshot.compile(topology.nodes()));
        initialSignal = generator.generateSignals(1).getFirst();
        processor = (node, input) -> new NodeProcessingResult(List.of(new Signal(SignalKind.INTERMEDIATE, input.frequencyState())));

        int maxSteps = nodeCount == 50 ? 200 : nodeCount == 500 ? 2_000 : 10_000;
        int maxHops = nodeCount == 50 ? 5 : nodeCount == 500 ? 8 : 10;

        if ("Threshold".equalsIgnoreCase(routingPolicyType)) {
            var resonancePolicy = new ResonanceThresholdRoutingPolicy(new ScalarResonanceMetric(), 0.5);
            config = new PropagationConfig(maxSteps, maxHops, resonancePolicy);
        } else {
            config = PropagationConfig.routeAll(maxSteps, maxHops);
        }
    }

    @Benchmark
    public void benchmarkReferencePropagation(Blackhole blackhole) {
        PropagationResult result = referenceEngine.propagate(
                topology.entryNode(),
                initialSignal,
                processor,
                config);
        blackhole.consume(result);
    }

    @Benchmark
    public void benchmarkCompactPropagation(Blackhole blackhole) {
        PropagationResult result = compactEngine.propagate(
                topology.entryNode(),
                initialSignal,
                processor,
                config);
        blackhole.consume(result);
    }

    @Benchmark
    public void benchmarkCompactCompilation(Blackhole blackhole) {
        blackhole.consume(CompactGraphSnapshot.compile(topology.nodes()));
    }
}
