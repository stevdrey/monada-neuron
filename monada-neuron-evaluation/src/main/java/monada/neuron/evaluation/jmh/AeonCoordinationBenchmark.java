package monada.neuron.evaluation.jmh;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonCoordinationResult;
import monada.neuron.aeon.AeonInput;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.aeon.DeterministicAeonCoordinator;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.GraphTopology;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
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
 * JMH microbenchmark for {@link DeterministicAeonCoordinator}.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class AeonCoordinationBenchmark {

    @Param({"5", "20"})
    private int inputCount;

    private DeterministicAeonCoordinator coordinator;
    private Aeon aeon;
    private List<AeonInput> inputs;
    private NodeProcessor processor;
    private PropagationConfig config;

    @Setup(Level.Trial)
    public void setup() {
        coordinator = new DeterministicAeonCoordinator(new DeterministicSignalPropagationEngine());
        var generator = new DeterministicWorkloadGenerator();
        GraphTopology topology = generator.generateGraph(50, 3);
        aeon = generator.generateAeon(AeonPurpose.REASONING, topology);

        var signals = generator.generateSignals(inputCount);
        inputs = signals.stream()
                .map(s -> new AeonInput(topology.entryNode().getId(), s))
                .toList();

        processor = (node, input) -> new NodeProcessingResult(List.of(new Signal(SignalKind.INTERMEDIATE, input.frequencyState())));
        config = PropagationConfig.routeAll(200, 4);
    }

    @Benchmark
    public void benchmarkDirectCoordination(Blackhole blackhole) {
        AeonCoordinationResult result = coordinator.coordinate(
                aeon,
                inputs,
                processor,
                config);
        blackhole.consume(result);
    }

    @Benchmark
    public void benchmarkContextualCoordination(Blackhole blackhole) {
        var budget = new CognitiveBudget(2_000, 2_000, 5_000);
        var context = new CognitiveContext(budget);
        try {
            AeonCoordinationResult result = coordinator.coordinate(
                    aeon,
                    inputs,
                    processor,
                    config,
                    context);
            blackhole.consume(result);
        } finally {
            context.close();
        }
    }
}
