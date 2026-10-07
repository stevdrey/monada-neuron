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
 * Combined Neuron-owned adapter cost (Issue #49): query encoding, bounded merge and deduplication,
 * opaque references, and result decoding with the production default codecs.
 *
 * <p>Only the {@code MonadaMemory} recall is replaced by a substitute store returning prebuilt results,
 * so this is the number to compare, qualitatively, with the real-store recall latency of the
 * integration evaluation. No store, no I/O.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@Threads(1)
public class ResonanceStoreAdapterBoundaryBenchmark {

    @Benchmark
    public ResonanceMemoryResponse adapterSideBoundary(AdapterBoundaryState state) {
        return state.boundaryAdapter.recall(state.request);
    }
}
