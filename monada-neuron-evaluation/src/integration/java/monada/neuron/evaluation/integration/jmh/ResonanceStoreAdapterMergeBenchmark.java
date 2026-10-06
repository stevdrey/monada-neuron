package monada.neuron.evaluation.integration.jmh;

import monada.neuron.memory.ResonanceMemoryResponse;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Cost of the adapter's bounded merge, deduplication, opaque-reference derivation, and result
 * construction over prebuilt store results (Issue #49).
 *
 * <p>Runs the production {@code ResonanceStoreMemoryAdapter.recall} with trivial constant codecs and a
 * substitute store, so encoding and decoding are near-zero and only the Neuron-side loop, merge, and
 * SHA-256 references remain. No store, no I/O.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@Threads(1)
public class ResonanceStoreAdapterMergeBenchmark {

    @Benchmark
    public ResonanceMemoryResponse mergeAndMaterialize(AdapterBoundaryState state) {
        return state.mergeOnlyAdapter.recall(state.request);
    }
}
