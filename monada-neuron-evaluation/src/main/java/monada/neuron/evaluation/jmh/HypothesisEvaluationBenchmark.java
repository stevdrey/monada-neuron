package monada.neuron.evaluation.jmh;

import monada.neuron.evaluation.BoundedHeapSelector;
import monada.neuron.evaluation.FullSortSelector;
import monada.neuron.evaluation.HypothesisScoringConfig;
import monada.neuron.evaluation.ReferenceHypothesisEvaluationPolicy;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.reasoning.HypothesisSet;
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

import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks answering: at what candidate count {@code N} and selection bound {@code K}
 * does a bounded heap beat a full sort, and what do scoring and end-to-end evaluation cost?
 *
 * <p>{@code scoreOnly} evaluates with {@code K = 0} so it isolates the scoring pass. The
 * {@code select*} benchmarks run the selectors over a pre-generated score array. The
 * {@code evaluate*} benchmarks measure the complete reference policy with each selector. Run with
 * {@code -prof gc} for allocation per operation. When {@code K >= N} both selectors rank all
 * candidates.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class HypothesisEvaluationBenchmark {

    private static final int EVIDENCE_PER_CANDIDATE = 4;

    @Param({"10", "100", "1000", "10000"})
    private int candidates;

    @Param({"1", "10", "100"})
    private int topK;

    private HypothesisSet hypotheses;
    private double[] scores;
    private BoundedHeapSelector heapSelector;
    private FullSortSelector sortSelector;
    private ReferenceHypothesisEvaluationPolicy heapPolicy;
    private ReferenceHypothesisEvaluationPolicy sortPolicy;

    @Setup(Level.Trial)
    public void setup() {
        var generator = new DeterministicWorkloadGenerator();
        hypotheses = generator.generateHypothesisSet(candidates, EVIDENCE_PER_CANDIDATE);
        scores = generator.generateHypothesisScores(candidates);
        heapSelector = new BoundedHeapSelector();
        sortSelector = new FullSortSelector();
        heapPolicy = new ReferenceHypothesisEvaluationPolicy(HypothesisScoringConfig.NONE, heapSelector);
        sortPolicy = new ReferenceHypothesisEvaluationPolicy(HypothesisScoringConfig.NONE, sortSelector);
    }

    @Benchmark
    public void scoreOnly(Blackhole blackhole) {
        blackhole.consume(heapPolicy.evaluate(hypotheses, 0));
    }

    @Benchmark
    public void selectBoundedHeap(Blackhole blackhole) {
        blackhole.consume(heapSelector.select(scores, topK));
    }

    @Benchmark
    public void selectFullSort(Blackhole blackhole) {
        blackhole.consume(sortSelector.select(scores, topK));
    }

    @Benchmark
    public void evaluateBoundedHeap(Blackhole blackhole) {
        blackhole.consume(heapPolicy.evaluate(hypotheses, topK));
    }

    @Benchmark
    public void evaluateFullSort(Blackhole blackhole) {
        blackhole.consume(sortPolicy.evaluate(hypotheses, topK));
    }
}
