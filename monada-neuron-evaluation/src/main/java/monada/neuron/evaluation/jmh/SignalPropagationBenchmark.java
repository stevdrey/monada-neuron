package monada.neuron.evaluation.jmh;

import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.GraphTopology;
import monada.neuron.model.Node;
import monada.neuron.resonance.ScalarResonanceMetric;
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
 * JMH microbenchmark for {@link DeterministicSignalPropagationEngine}.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class SignalPropagationBenchmark {

    @Param({"50", "200"})
    private int nodeCount;

    @Param({"RouteAll", "Threshold"})
    private String routingPolicyType;

    private DeterministicSignalPropagationEngine engine;
    private GraphTopology topology;
    private Signal initialSignal;
    private NodeProcessor processor;
    private PropagationConfig config;

    @Setup(Level.Trial)
    public void setup() {
        engine = new DeterministicSignalPropagationEngine();
        var generator = new DeterministicWorkloadGenerator();
        topology = generator.generateGraph(nodeCount, 4);
        initialSignal = generator.generateSignals(1).getFirst();
        processor = (node, input) -> new NodeProcessingResult(List.of(new Signal(SignalKind.INTERMEDIATE, input.frequencyState())));

        if ("Threshold".equalsIgnoreCase(routingPolicyType)) {
            var resonancePolicy = new ResonanceThresholdRoutingPolicy(new ScalarResonanceMetric(), 0.5);
            config = new PropagationConfig(500, 6, resonancePolicy);
        } else {
            config = PropagationConfig.routeAll(500, 6);
        }
    }

    @Benchmark
    public void benchmarkPropagation(Blackhole blackhole) {
        PropagationResult result = engine.propagate(
                topology.entryNode(),
                initialSignal,
                processor,
                config);
        blackhole.consume(result);
    }
}
