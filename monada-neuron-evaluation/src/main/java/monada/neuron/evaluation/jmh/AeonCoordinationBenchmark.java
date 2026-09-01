package monada.neuron.evaluation.jmh;

import monada.neuron.aeon.Aeon;
import monada.neuron.aeon.AeonCoordinationResult;
import monada.neuron.aeon.AeonInput;
import monada.neuron.aeon.AeonPurpose;
import monada.neuron.aeon.BoundedParallelAeonCoordinator;
import monada.neuron.aeon.ContextualParallelism;
import monada.neuron.aeon.DeterministicAeonCoordinator;
import monada.neuron.context.CognitiveBudget;
import monada.neuron.context.CognitiveContext;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.GraphTopology;
import monada.neuron.runtime.graph.DeterministicSignalPropagationEngine;
import monada.neuron.runtime.graph.PropagationConfig;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmark comparing sequential {@link DeterministicAeonCoordinator} and
 * bounded parallel {@link BoundedParallelAeonCoordinator} across independent input counts,
 * graph scales, and worker bounds.
 *
 * <p>The parallel coordinator uses a threshold of one so this benchmark measures the parallel
 * implementation at every configured input count. Production threshold selection is based on the
 * resulting crossover study rather than the coordinator's current default.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class AeonCoordinationBenchmark {

    @Param({"1", "2", "3", "4", "5", "8", "10", "16", "32", "64", "128"})
    private int inputCount;

    @Param({"small", "medium", "large"})
    private String graphProfile;

    @Param({"1", "2", "4"})
    private int workerCount;

    private DeterministicAeonCoordinator sequentialCoordinator;
    private BoundedParallelAeonCoordinator parallelCoordinator;
    private Aeon aeon;
    private List<AeonInput> inputs;
    private NodeProcessor processor;
    private PropagationConfig config;

    @Setup(Level.Trial)
    public void setup() {
        var engine = new DeterministicSignalPropagationEngine();
        sequentialCoordinator = new DeterministicAeonCoordinator(engine);
        parallelCoordinator = new BoundedParallelAeonCoordinator(
                engine,
                workerCount,
                1,
                ContextualParallelism.EXPERIMENTAL_PARALLEL);

        var generator = new DeterministicWorkloadGenerator();
        GraphTopology topology = graphTopology(generator);
        aeon = generator.generateAeon(AeonPurpose.REASONING, topology);

        var signals = generator.generateSignals(inputCount);
        inputs = signals.stream()
                .map(s -> new AeonInput(topology.entryNode().getId(), s))
                .toList();

        processor = (node, input) -> new NodeProcessingResult(List.of(new Signal(SignalKind.INTERMEDIATE, input.frequencyState())));
        config = PropagationConfig.routeAll(200, 4);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (parallelCoordinator != null) {
            parallelCoordinator.close();
        }
    }

    @Benchmark
    public void benchmarkSequentialDirect(Blackhole blackhole) {
        AeonCoordinationResult result = sequentialCoordinator.coordinate(
                aeon,
                inputs,
                processor,
                config);
        blackhole.consume(result);
    }

    @Benchmark
    public void benchmarkParallelDirect(Blackhole blackhole) {
        AeonCoordinationResult result = parallelCoordinator.coordinate(
                aeon,
                inputs,
                processor,
                config);
        blackhole.consume(result);
    }

    @Benchmark
    public void benchmarkSequentialContextual(Blackhole blackhole) {
        var budget = new CognitiveBudget(100_000, 500_000, 500_000);
        var context = new CognitiveContext(budget);
        try {
            AeonCoordinationResult result = sequentialCoordinator.coordinate(
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

    @Benchmark
    public void benchmarkParallelContextual(Blackhole blackhole) {
        var budget = new CognitiveBudget(100_000, 500_000, 500_000);
        var context = new CognitiveContext(budget);
        try {
            AeonCoordinationResult result = parallelCoordinator.coordinate(
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

    private GraphTopology graphTopology(DeterministicWorkloadGenerator generator) {
        return switch (graphProfile) {
            case "small" -> generator.generateGraph(50, 3);
            case "medium" -> generator.generateGraph(200, 5);
            case "large" -> generator.generateGraph(1_000, 4);
            default -> throw new IllegalArgumentException("unknown graphProfile: " + graphProfile);
        };
    }
}
