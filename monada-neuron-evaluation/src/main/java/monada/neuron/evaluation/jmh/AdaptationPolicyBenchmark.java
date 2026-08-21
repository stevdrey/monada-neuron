package monada.neuron.evaluation.jmh;

import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evolution.AdaptationConfig;
import monada.neuron.evolution.AdaptationDecision;
import monada.neuron.evolution.DeterministicBaselineAdaptationPolicy;
import monada.neuron.evolution.FeedbackInput;
import monada.neuron.evolution.NoOpAdaptationPolicy;
import monada.neuron.model.FrequencyState;
import monada.neuron.model.Node;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmark comparing {@link NoOpAdaptationPolicy} vs {@link DeterministicBaselineAdaptationPolicy}.
 *
 * <p>Separates pure policy decision arithmetic from state-mutating transition benchmarks to guarantee
 * equivalent, controlled state per invocation without unbounded history list accumulation:
 * <ul>
 *   <li>{@link #benchmarkNoOpPolicy}: Non-mutating no-op policy baseline.</li>
 *   <li>{@link #benchmarkBaselinePolicyDecisionArithmetic}: Pure mathematical candidate-state derivation without {@link Node} mutation.</li>
 *   <li>{@link #benchmarkBaselinePolicyFull}: Complete adaptation (arithmetic + transition + energy update) against fresh node state.</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class AdaptationPolicyBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkPlan {
        NoOpAdaptationPolicy noOpPolicy;
        DeterministicBaselineAdaptationPolicy baselinePolicy;
        AdaptationConfig config;
        Node staticNode;
        FeedbackInput staticFeedback;
        FrequencyState baseState;
        double baseEnergy;

        @Setup(Level.Trial)
        public void setupTrial() {
            noOpPolicy = NoOpAdaptationPolicy.INSTANCE;
            config = AdaptationConfig.DEFAULT;
            baselinePolicy = new DeterministicBaselineAdaptationPolicy(config);

            var generator = new DeterministicWorkloadGenerator();
            var topology = generator.generateGraph(1, 0);
            staticNode = topology.entryNode();
            var signals = generator.generateSignals(1);
            staticFeedback = FeedbackInput.ofTarget(staticNode.getId(), signals.getFirst(), 0.8);

            baseState = new FrequencyState(1.0, 440.0, 0.0);
            baseEnergy = 1.0;
        }
    }

    @State(Scope.Thread)
    public static class InvocationNodeHolder {
        Node node;
        FeedbackInput feedback;

        @Setup(Level.Invocation)
        public void setupInvocation(BenchmarkPlan plan) {
            node = new Node.Builder()
                    .id(plan.staticNode.getId())
                    .type(plan.staticNode.getType())
                    .frequencyState(plan.baseState)
                    .energy(plan.baseEnergy)
                    .build();
            var targetSignal = new Signal(SignalKind.FEEDBACK, new FrequencyState(1.2, 450.0, 0.5));
            feedback = FeedbackInput.ofTarget(node.getId(), targetSignal, 0.8);
        }

    }

    /**
     * Benchmarks {@link NoOpAdaptationPolicy#adapt(Node, FeedbackInput)}, which is strictly non-mutating.
     */
    @Benchmark
    public void benchmarkNoOpPolicy(BenchmarkPlan plan, Blackhole blackhole) {
        AdaptationDecision decision = plan.noOpPolicy.adapt(plan.staticNode, plan.staticFeedback);
        blackhole.consume(decision);
    }

    /**
     * Benchmarks the pure mathematical decision logic of {@link DeterministicBaselineAdaptationPolicy}
     * (candidate amplitude, frequency, phase wrapping, energy scaling) without invoking {@link Node#transition(FrequencyState)}.
     */
    @Benchmark
    public void benchmarkBaselinePolicyDecisionArithmetic(BenchmarkPlan plan, Blackhole blackhole) {
        double score = plan.staticFeedback.score();
        var previousState = plan.baseState;
        double previousEnergy = plan.baseEnergy;
        var targetSignal = plan.staticFeedback.targetSignal();
        var targetState = targetSignal.frequencyState();

        double deltaAmplitude = targetState.amplitude() - previousState.amplitude();
        double candidateAmplitude = Math.clamp(
                previousState.amplitude() + plan.config.learningRate() * score * deltaAmplitude,
                plan.config.minAmplitude(),
                plan.config.maxAmplitude());

        double deltaFrequency = targetState.frequency() - previousState.frequency();
        double candidateFrequency = Math.clamp(
                previousState.frequency() + plan.config.learningRate() * score * deltaFrequency,
                plan.config.minFrequency(),
                plan.config.maxFrequency());

        double deltaPhase = StrictMath.IEEEremainder(targetState.phase() - previousState.phase(), 2.0 * Math.PI);
        if (deltaPhase < -Math.PI) {
            deltaPhase += 2.0 * Math.PI;
        } else if (deltaPhase > Math.PI) {
            deltaPhase -= 2.0 * Math.PI;
        }

        double candidatePhase = StrictMath.IEEEremainder(
                previousState.phase() + plan.config.learningRate() * score * deltaPhase,
                2.0 * Math.PI);
        if (candidatePhase < 0.0) {
            candidatePhase += 2.0 * Math.PI;
        }

        double candidateEnergy = Math.clamp(
                previousEnergy + plan.config.learningRate() * score * plan.config.energyStep(),
                plan.config.minEnergy(),
                plan.config.maxEnergy());

        var newState = new FrequencyState(candidateAmplitude, candidateFrequency, candidatePhase);
        boolean stateChanged = !newState.equals(previousState);
        boolean energyChanged = Double.compare(candidateEnergy, previousEnergy) != 0;

        var decision = new AdaptationDecision(
                plan.staticFeedback.targetNodeId(),
                stateChanged || energyChanged,
                previousState,
                newState,
                previousEnergy,
                candidateEnergy);
        blackhole.consume(decision);
    }

    /**
     * Benchmarks full {@link DeterministicBaselineAdaptationPolicy#adapt(Node, FeedbackInput)} on a
     * guaranteed fresh, pristine {@link Node} instance per invocation.
     */
    @Benchmark
    public void benchmarkBaselinePolicyFull(BenchmarkPlan plan, InvocationNodeHolder holder, Blackhole blackhole) {
        AdaptationDecision decision = plan.baselinePolicy.adapt(holder.node, holder.feedback);
        blackhole.consume(decision);
    }
}
