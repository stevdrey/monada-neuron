package monada.neuron.evaluation.jmh;

import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.AdaptationDecision;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.FeedbackInput;
import monada.neuron.evolution.NoOpAdaptationPolicy;
import monada.neuron.model.Node;
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

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmark comparing {@link NoOpAdaptationPolicy} vs {@link DeterministicBaselineAdaptationPolicy}.
 *
 * <p>Uses a large power-of-two pool (131,072 entries) of pre-allocated, independent {@link Node} fixtures
 * recreated at {@link Level#Iteration}. Benchmark invocations cycle through the pool using branchless bitmasking,
 * ensuring each invocation operates on fresh nodes and preventing unbounded {@code Node.history} accumulation
 * from distorting the measured adaptation latency.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class AdaptationPolicyBenchmark {

    private static final int POOL_SIZE = 131_072;
    private static final int POOL_MASK = POOL_SIZE - 1;

    private NoOpAdaptationPolicy noOpPolicy;
    private DeterministicBaselineAdaptationPolicy baselinePolicy;
    private Node[] nodesArray;
    private FeedbackInput[] feedbacksArray;
    private int index;

    @Setup(Level.Trial)
    public void setupTrial() {
        noOpPolicy = NoOpAdaptationPolicy.INSTANCE;
        baselinePolicy = new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT);
    }

    /**
     * Re-creates the 131,072 pristine node fixtures at the start of each iteration so that
     * history list mutations do not accumulate across warmup and measurement iterations.
     */
    @Setup(Level.Iteration)
    public void setupIteration() {
        var generator = new DeterministicWorkloadGenerator();
        var topology = generator.generateGraph(POOL_SIZE, 0);
        var nodes = topology.nodes();
        var signals = generator.generateSignals(POOL_SIZE);

        var feedbacks = new ArrayList<FeedbackInput>(POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            feedbacks.add(FeedbackInput.ofTarget(nodes.get(i).getId(), signals.get(i), 0.8));
        }

        nodesArray = nodes.toArray(new Node[0]);
        feedbacksArray = feedbacks.toArray(new FeedbackInput[0]);
        index = 0;
    }

    /**
     * Benchmarks a single invocation of {@link NoOpAdaptationPolicy#adapt(Node, FeedbackInput)}.
     */
    @Benchmark
    public void benchmarkNoOpPolicy(Blackhole blackhole) {
        int idx = (index++) & POOL_MASK;
        AdaptationDecision decision = noOpPolicy.adapt(nodesArray[idx], feedbacksArray[idx]);
        blackhole.consume(decision);
    }

    /**
     * Benchmarks a single invocation of {@link DeterministicBaselineAdaptationPolicy#adapt(Node, FeedbackInput)},
     * including candidate frequency/energy arithmetic, node state transition, and energy update.
     */
    @Benchmark
    public void benchmarkBaselinePolicy(Blackhole blackhole) {
        int idx = (index++) & POOL_MASK;
        AdaptationDecision decision = baselinePolicy.adapt(nodesArray[idx], feedbacksArray[idx]);
        blackhole.consume(decision);
    }
}
