package monada.neuron.evaluation.jmh;

import monada.neuron.context.CognitiveBudget;
import monada.neuron.evaluation.workload.DeterministicActionFixture;
import monada.neuron.evaluation.workload.DeterministicMemoryFixture;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.CognitiveCycleSetup;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.NoOpAdaptationPolicy;
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
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link #benchmarkCognitiveCycleNoOp}: Stable full-cycle execution with {@link NoOpAdaptationPolicy}
 *       which performs zero node mutations, ensuring pristine cognitive state across all invocations.</li>
 *   <li>{@link #benchmarkCognitiveCycleBaseline}: Full-cycle execution with {@link DeterministicBaselineAdaptationPolicy}
 *       operating on a fresh cognitive setup per invocation so adapted state and node history do not
 *       accumulate and make successive invocations non-comparable.</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class CognitiveCycleBenchmark {

    @State(Scope.Benchmark)
    public static class StableBenchmarkPlan {
        CognitiveCycleSetup noOpSetup;
        List<Signal> initialSignals;
        CognitiveBudget budget;

        @Setup(Level.Trial)
        public void setupTrial() {
            var generator = new DeterministicWorkloadGenerator();
            var perceptionTop = generator.generateGraph(30, 3);
            var reasoningTop = generator.generateGraph(30, 3);
            var memoryPort = new DeterministicMemoryFixture();
            var actionCap = new DeterministicActionFixture();

            noOpSetup = generator.generateFullCycleSetup(
                    perceptionTop,
                    reasoningTop,
                    NoOpAdaptationPolicy.INSTANCE,
                    memoryPort,
                    actionCap);
            initialSignals = generator.generateSignals(3);
            budget = new CognitiveBudget(2_000, 2_000, 5_000);
        }
    }

    @State(Scope.Thread)
    public static class FreshBaselineCycleHolder {
        CognitiveCycleSetup baselineSetup;

        @Setup(Level.Invocation)
        public void setupInvocation() {
            var generator = new DeterministicWorkloadGenerator();
            var perceptionTop = generator.generateGraph(30, 3);
            var reasoningTop = generator.generateGraph(30, 3);
            var policy = new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT);
            var memoryPort = new DeterministicMemoryFixture();
            var actionCap = new DeterministicActionFixture();

            baselineSetup = generator.generateFullCycleSetup(
                    perceptionTop,
                    reasoningTop,
                    policy,
                    memoryPort,
                    actionCap);
        }
    }

    /**
     * Benchmarks full 5-stage cognitive cycle with {@link NoOpAdaptationPolicy}, guaranteeing
     * non-mutating, pristine node state across all invocations.
     */
    @Benchmark
    public void benchmarkCognitiveCycleNoOp(StableBenchmarkPlan plan, Blackhole blackhole) {
        CognitiveCycleResult result = plan.noOpSetup.cycle().execute(
                plan.noOpSetup.monad(),
                plan.initialSignals,
                plan.budget);
        blackhole.consume(result);
    }

    /**
     * Benchmarks full 5-stage cognitive cycle with {@link DeterministicBaselineAdaptationPolicy}
     * with fresh setup per invocation so adapted state and node history do not accumulate across
     * successive cycles.
     */
    @Benchmark
    public void benchmarkCognitiveCycleBaseline(
            StableBenchmarkPlan plan,
            FreshBaselineCycleHolder holder,
            Blackhole blackhole) {
        CognitiveCycleResult result = holder.baselineSetup.cycle().execute(
                holder.baselineSetup.monad(),
                plan.initialSignals,
                plan.budget);
        blackhole.consume(result);
    }
}
