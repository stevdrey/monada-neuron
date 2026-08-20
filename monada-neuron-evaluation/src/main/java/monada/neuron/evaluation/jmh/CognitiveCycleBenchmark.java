package monada.neuron.evaluation.jmh;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.evaluation.workload.DeterministicActionFixture;
import monada.neuron.evaluation.workload.DeterministicMemoryFixture;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.CognitiveCycleSetup;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.monad.CognitiveCycleResult;
import monada.neuron.signal.Signal;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmark for full {@link monada.neuron.monad.DeterministicCognitiveCycle}.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class CognitiveCycleBenchmark {

    private CognitiveCycleSetup setup;
    private List<Signal> initialSignals;
    private CognitiveBudget budget;

    @Setup(Level.Trial)
    public void setup() {
        var generator = new DeterministicWorkloadGenerator();
        var perceptionTop = generator.generateGraph(30, 3);
        var reasoningTop = generator.generateGraph(30, 3);
        var policy = new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT);
        var memoryPort = new DeterministicMemoryFixture();
        var actionCap = new DeterministicActionFixture();

        setup = generator.generateFullCycleSetup(perceptionTop, reasoningTop, policy, memoryPort, actionCap);
        initialSignals = generator.generateSignals(3);
        budget = new CognitiveBudget(2_000, 2_000, 5_000);
    }

    @Benchmark
    public void benchmarkFullCognitiveCycle(Blackhole blackhole) {
        CognitiveCycleResult result = setup.cycle().execute(setup.monad(), initialSignals, budget);
        blackhole.consume(result);
    }
}
