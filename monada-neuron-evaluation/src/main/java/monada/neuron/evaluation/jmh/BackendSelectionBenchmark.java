package monada.neuron.evaluation.jmh;

import monada.neuron.evaluation.workload.DeterministicWorkloadGenerator;
import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.BatchResonanceEvaluator;
import monada.neuron.resonance.FrequencyStateBatch;
import monada.neuron.resonance.ScalarBatchResonanceEvaluator;
import monada.neuron.runtime.graph.CompactGraphSnapshot;
import monada.neuron.runtime.selection.BackendSelection;
import monada.neuron.runtime.selection.ExecutionPreference;
import monada.neuron.runtime.selection.RuntimeBackendSelector;
import monada.neuron.runtime.selection.RuntimeSelectionConfig;
import monada.neuron.runtime.selection.SelectingBatchResonanceEvaluator;
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
 * JMH microbenchmarks measuring control-plane dispatch overhead of {@link RuntimeBackendSelector}.
 *
 * <p>Validates that selection overhead is strictly negligible (&lt; 100 ns) and that
 * selecting adapters introduce no noticeable overhead relative to raw execution.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class BackendSelectionBenchmark {

    @Param({"4", "64", "1000"})
    private int batchSize;

    private RuntimeBackendSelector autoSelector;
    private RuntimeBackendSelector referenceSelector;
    private SelectingBatchResonanceEvaluator selectingEvaluator;
    private BatchResonanceEvaluator directScalarEvaluator;

    private FrequencyStateBatch firstBatch;
    private FrequencyStateBatch secondBatch;
    private double[] resultsBuffer;
    private CompactGraphSnapshot snapshot;

    @Setup(Level.Trial)
    public void setup() {
        autoSelector = RuntimeBackendSelector.autoSelector();
        referenceSelector = RuntimeBackendSelector.referenceSelector();
        selectingEvaluator = new SelectingBatchResonanceEvaluator(autoSelector);
        directScalarEvaluator = ScalarBatchResonanceEvaluator.INSTANCE;

        var generator = new DeterministicWorkloadGenerator();
        var batchPair = generator.generateFrequencyStateBatches(batchSize);
        firstBatch = batchPair.first();
        secondBatch = batchPair.second();
        resultsBuffer = new double[batchSize];

        var topology = generator.generateGraph(50, 3);
        snapshot = CompactGraphSnapshot.compile(topology.nodes());
    }

    @Benchmark
    public void benchmarkResonanceSelectionAuto(Blackhole blackhole) {
        blackhole.consume(autoSelector.selectResonance(batchSize));
    }

    @Benchmark
    public void benchmarkResonanceSelectionReference(Blackhole blackhole) {
        blackhole.consume(referenceSelector.selectResonance(batchSize));
    }

    @Benchmark
    public void benchmarkGraphSelection(Blackhole blackhole) {
        blackhole.consume(autoSelector.selectGraphPropagation(snapshot, false));
    }

    @Benchmark
    public void benchmarkAeonSelection(Blackhole blackhole) {
        blackhole.consume(autoSelector.selectAeonCoordinator(batchSize, false, true));
    }

    @Benchmark
    public void benchmarkDirectScalarBatch(Blackhole blackhole) {
        directScalarEvaluator.scoreBatch(firstBatch, secondBatch, resultsBuffer, 0, batchSize);
        blackhole.consume(resultsBuffer);
    }

    @Benchmark
    public void benchmarkSelectingBatch(Blackhole blackhole) {
        selectingEvaluator.scoreBatch(firstBatch, secondBatch, resultsBuffer, 0, batchSize);
        blackhole.consume(resultsBuffer);
    }
}
