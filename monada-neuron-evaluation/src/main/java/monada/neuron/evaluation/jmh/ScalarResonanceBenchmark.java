package monada.neuron.evaluation.jmh;

import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.FrequencyStatePair;
import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.ScalarResonanceMetric;
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
 * JMH microbenchmark for {@link ScalarResonanceMetric#score(FrequencyState, FrequencyState)}.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class ScalarResonanceBenchmark {

    @Param({"100", "1000", "10000"})
    private int batchSize;

    private ScalarResonanceMetric metric;
    private List<FrequencyStatePair> pairs;

    @Setup(Level.Trial)
    public void setup() {
        metric = new ScalarResonanceMetric();
        var generator = new DeterministicWorkloadGenerator();
        pairs = generator.generateFrequencyStatePairs(batchSize);
    }

    @Benchmark
    public void benchmarkBatchResonance(Blackhole blackhole) {
        for (var pair : pairs) {
            double score = metric.score(pair.first(), pair.second());
            blackhole.consume(score);
        }
    }
}
