package monada.neuron.evaluation.jmh;

import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.AdaptationDecision;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.FeedbackInput;
import monada.neuron.evolution.NoOpAdaptationPolicy;
import monada.neuron.model.Node;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmark comparing {@link NoOpAdaptationPolicy} vs {@link DeterministicBaselineAdaptationPolicy}.
 *
 * <p>Resets fresh node state fixtures at {@link Level#Iteration} to prevent mutation history accumulation
 * and ensure stable, reproducible per-operation measurements across benchmark iterations.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class AdaptationPolicyBenchmark {

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
     * Re-creates pristine nodes at the start of each iteration so history does not accumulate across iterations.
     */
    @Setup(Level.Iteration)
    public void setupIteration() {
        var generator = new DeterministicWorkloadGenerator();
        var topology = generator.generateGraph(100, 0);
        var nodes = topology.nodes();
        var signals = generator.generateSignals(100);

        var feedbacks = new ArrayList<FeedbackInput>(100);
        for (int i = 0; i < 100; i++) {
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
        int idx = index++;
        if (idx >= nodesArray.length) {
            idx = 0;
            index = 0;
        }
        AdaptationDecision decision = noOpPolicy.adapt(nodesArray[idx], feedbacksArray[idx]);
        blackhole.consume(decision);
    }

    /**
     * Benchmarks a single invocation of {@link DeterministicBaselineAdaptationPolicy#adapt(Node, FeedbackInput)},
     * including policy calculation, state transition, and energy update.
     */
    @Benchmark
    public void benchmarkBaselinePolicy(Blackhole blackhole) {
        int idx = index++;
        if (idx >= nodesArray.length) {
            idx = 0;
            index = 0;
        }
        AdaptationDecision decision = baselinePolicy.adapt(nodesArray[idx], feedbacksArray[idx]);
        blackhole.consume(decision);
    }
}
