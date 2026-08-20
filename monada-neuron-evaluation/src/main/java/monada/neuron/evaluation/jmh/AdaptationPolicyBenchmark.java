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
    private List<Node> nodes;
    private List<FeedbackInput> feedbacks;

    @Setup(Level.Trial)
    public void setup() {
        noOpPolicy = NoOpAdaptationPolicy.INSTANCE;
        baselinePolicy = new DeterministicBaselineAdaptationPolicy(AdaptationConfig.DEFAULT);

        var generator = new DeterministicWorkloadGenerator();
        var topology = generator.generateGraph(100, 0);
        nodes = topology.nodes();

        var signals = generator.generateSignals(100);
        feedbacks = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            feedbacks.add(FeedbackInput.ofTarget(nodes.get(i).getId(), signals.get(i), 0.8));
        }
    }

    @Benchmark
    public void benchmarkNoOpPolicy(Blackhole blackhole) {
        for (int i = 0; i < nodes.size(); i++) {
            AdaptationDecision decision = noOpPolicy.adapt(nodes.get(i), feedbacks.get(i));
            blackhole.consume(decision);
        }
    }

    @Benchmark
    public void benchmarkBaselinePolicy(Blackhole blackhole) {
        for (int i = 0; i < nodes.size(); i++) {
            AdaptationDecision decision = baselinePolicy.adapt(nodes.get(i), feedbacks.get(i));
            blackhole.consume(decision);
        }
    }
}
