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
 * <p>{@code scoreOnly} runs the reference policy with a no-op selector, so it isolates the scoring
 * pass (plus a constant, empty result) and depends only on {@code N} ({@link ScoringState}). The
 * {@code select*} benchmarks run the selectors over a pre-generated score array and the
 * {@code evaluate*} benchmarks measure the complete reference policy with each selector; both
 * depend on {@code N} and {@code K} ({@link SelectionState}). When
 * {@code K >= N} both selectors rank every candidate. All policies use evidence-only scoring
 * ({@code HypothesisScoringConfig.NONE}); a resonance component adds a second N-sized array. Run with
 * {@code -prof gc} for allocation per operation.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class HypothesisEvaluationBenchmark {

    private static final int EVIDENCE_PER_CANDIDATE = 4;
    private static final int[] NO_SELECTION = new int[0];

    /** Workload for the scoring pass: parameterized by candidate count only. */
    @State(Scope.Benchmark)
    public static class ScoringState {

        @Param({"10", "100", "1000", "10000"})
        private int candidates;

        HypothesisSet hypotheses;
        ReferenceHypothesisEvaluationPolicy policy;

        @Setup(Level.Trial)
        public void setup() {
            hypotheses = new DeterministicWorkloadGenerator()
                    .generateHypothesisSet(candidates, EVIDENCE_PER_CANDIDATE);
            // A no-op selector keeps the selector (including its NaN scan) out of the scoring measurement.
            policy = new ReferenceHypothesisEvaluationPolicy(
                    HypothesisScoringConfig.NONE, (scores, k) -> NO_SELECTION);
        }
    }

    /** Workload for selection and end-to-end evaluation: candidate count and selection bound. */
    @State(Scope.Benchmark)
    public static class SelectionState {

        @Param({"10", "100", "1000", "10000"})
        private int candidates;

        @Param({"1", "10", "100"})
        private int topK;

        HypothesisSet hypotheses;
        double[] scores;
        BoundedHeapSelector heapSelector;
        FullSortSelector sortSelector;
        ReferenceHypothesisEvaluationPolicy heapPolicy;
        ReferenceHypothesisEvaluationPolicy sortPolicy;

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
    }

    @Benchmark
    public void scoreOnly(ScoringState state, Blackhole blackhole) {
        blackhole.consume(state.policy.evaluate(state.hypotheses, 0));
    }

    @Benchmark
    public void selectBoundedHeap(SelectionState state, Blackhole blackhole) {
        blackhole.consume(state.heapSelector.select(state.scores, state.topK));
    }

    @Benchmark
    public void selectFullSort(SelectionState state, Blackhole blackhole) {
        blackhole.consume(state.sortSelector.select(state.scores, state.topK));
    }

    @Benchmark
    public void evaluateBoundedHeap(SelectionState state, Blackhole blackhole) {
        blackhole.consume(state.heapPolicy.evaluate(state.hypotheses, state.topK));
    }

    @Benchmark
    public void evaluateFullSort(SelectionState state, Blackhole blackhole) {
        blackhole.consume(state.sortPolicy.evaluate(state.hypotheses, state.topK));
    }
}
