package monada.neuron.evaluation.jmh;

import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator.FrequencyStatePair;
import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.AdaptiveBatchResonanceEvaluator;
import monada.neuron.resonance.BatchResonanceEvaluator;
import monada.neuron.resonance.FrequencyStateBatch;
import monada.neuron.resonance.ScalarBatchResonanceEvaluator;
import monada.neuron.resonance.ScalarResonanceMetric;
import monada.neuron.resonance.VectorBatchResonanceEvaluator;
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
 * JMH microbenchmarks for scalar and SIMD vector batch resonance evaluation.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class ScalarResonanceBenchmark {

    @Param({"1", "4", "8", "16", "32", "64", "128", "256", "1000", "10000", "100000"})
    private int batchSize;

    private ScalarResonanceMetric scalarMetric;
    private BatchResonanceEvaluator scalarBatchEvaluator;
    private BatchResonanceEvaluator vectorBatchEvaluator;
    private BatchResonanceEvaluator adaptiveBatchEvaluator;

    private FrequencyStatePair[] pairsArray;
    private FrequencyState[] firstArray;
    private FrequencyState[] secondArray;

    private FrequencyStateBatch firstBatch;
    private FrequencyStateBatch secondBatch;

    private double[] resultsBuffer;
    private int pairIndex;

    @Setup(Level.Trial)
    public void setup() {
        scalarMetric = new ScalarResonanceMetric();
        scalarBatchEvaluator = ScalarBatchResonanceEvaluator.INSTANCE;
        vectorBatchEvaluator = VectorBatchResonanceEvaluator.INSTANCE;
        adaptiveBatchEvaluator = AdaptiveBatchResonanceEvaluator.INSTANCE;

        var generator = new DeterministicWorkloadGenerator();
        var pairs = generator.generateFrequencyStatePairs(batchSize);
        pairsArray = pairs.toArray(new FrequencyStatePair[0]);

        firstArray = new FrequencyState[batchSize];
        secondArray = new FrequencyState[batchSize];
        for (int i = 0; i < batchSize; i++) {
            firstArray[i] = pairsArray[i].first();
            secondArray[i] = pairsArray[i].second();
        }

        var batchPair = generator.generateFrequencyStateBatches(batchSize);
        firstBatch = batchPair.first();
        secondBatch = batchPair.second();

        resultsBuffer = new double[batchSize];
        pairIndex = 0;
    }

    /**
     * Benchmarks a single scalar resonance score calculation cycling across pre-generated pairs.
     * Reports latency per individual score calculation in nanoseconds.
     */
    @Benchmark
    public void benchmarkSingleScore(Blackhole blackhole) {
        int idx = pairIndex++;
        if (idx >= pairsArray.length) {
            idx = 0;
            pairIndex = 0;
        }
        var pair = pairsArray[idx];
        double score = scalarMetric.score(pair.first(), pair.second());
        blackhole.consume(score);
    }

    /**
     * Benchmarks baseline loop-based batch evaluation with ScalarResonanceMetric.
     */
    @Benchmark
    public void benchmarkBatch(Blackhole blackhole) {
        for (var pair : pairsArray) {
            double score = scalarMetric.score(pair.first(), pair.second());
            blackhole.consume(score);
        }
    }

    /**
     * Benchmarks ScalarBatchResonanceEvaluator over Structure-of-Arrays (SoA) contiguous batches.
     */
    @Benchmark
    public void benchmarkScalarBatchSoA(Blackhole blackhole) {
        scalarBatchEvaluator.scoreBatch(firstBatch, secondBatch, resultsBuffer, 0, batchSize);
        blackhole.consume(resultsBuffer);
    }

    /**
     * Benchmarks VectorBatchResonanceEvaluator over Structure-of-Arrays (SoA) contiguous batches.
     */
    @Benchmark
    public void benchmarkVectorBatchSoA(Blackhole blackhole) {
        vectorBatchEvaluator.scoreBatch(firstBatch, secondBatch, resultsBuffer, 0, batchSize);
        blackhole.consume(resultsBuffer);
    }

    /**
     * Benchmarks AdaptiveBatchResonanceEvaluator over Structure-of-Arrays (SoA) contiguous batches.
     */
    @Benchmark
    public void benchmarkAdaptiveBatchSoA(Blackhole blackhole) {
        adaptiveBatchEvaluator.scoreBatch(firstBatch, secondBatch, resultsBuffer, 0, batchSize);
        blackhole.consume(resultsBuffer);
    }

    /**
     * Benchmarks ScalarBatchResonanceEvaluator over Array-of-Objects (AoO).
     */
    @Benchmark
    public void benchmarkScalarBatchAoO(Blackhole blackhole) {
        scalarBatchEvaluator.scoreBatch(firstArray, secondArray, resultsBuffer, 0, batchSize);
        blackhole.consume(resultsBuffer);
    }

    /**
     * Benchmarks VectorBatchResonanceEvaluator over Array-of-Objects (AoO).
     */
    @Benchmark
    public void benchmarkVectorBatchAoO(Blackhole blackhole) {
        vectorBatchEvaluator.scoreBatch(firstArray, secondArray, resultsBuffer, 0, batchSize);
        blackhole.consume(resultsBuffer);
    }
}
