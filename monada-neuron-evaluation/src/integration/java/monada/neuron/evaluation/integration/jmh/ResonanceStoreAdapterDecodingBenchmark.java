package monada.neuron.evaluation.integration.jmh;

import monada.neuron.resonance.adapter.HashedSignalDecoder;
import monada.neuron.resonance.adapter.RecalledSignalDecoder;
import monada.neuron.signal.Signal;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * {@code RecalledSignalDecoder.decode(String)} with the production {@link HashedSignalDecoder}
 * (Issue #49) over deterministic ASCII content: 64 characters (integration-fixture sized), 1 KiB, and
 * 16 KiB to observe how the SHA-256 of the content scales.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
public class ResonanceStoreAdapterDecodingBenchmark {

    @Param({"64", "1024", "16384"})
    public int contentLength;

    private RecalledSignalDecoder decoder;
    private String content;

    @Setup(Level.Trial)
    public void setUp() {
        decoder = new HashedSignalDecoder();
        content = AdapterBoundaryScenario.content("recalled", contentLength);
        if (!decoder.decode(content).equals(decoder.decode(content))) {
            throw new IllegalStateException("Decoder must be deterministic");
        }
    }

    @Benchmark
    public Signal decode() {
        return decoder.decode(content);
    }
}
