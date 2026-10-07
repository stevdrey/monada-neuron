package monada.neuron.evaluation.integration.jmh;

import monada.neuron.resonance.adapter.OpaqueReference;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Opaque-reference derivation (SHA-256, truncated hex) for one UUID-shaped atom id (Issue #49), so its
 * contribution to the merge cost is {@code references per recall x this latency}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
public class ResonanceStoreAdapterOpaqueReferenceBenchmark {

    private OpaqueReference reference;
    private String atomId;

    @Setup(Level.Trial)
    public void setUp() {
        reference = new OpaqueReference();
        atomId = UUID.nameUUIDFromBytes("atom".getBytes(StandardCharsets.UTF_8)).toString();
        if (!reference.of(atomId).equals(reference.of(atomId))) {
            throw new IllegalStateException("Reference must be deterministic");
        }
    }

    @Benchmark
    public String reference() {
        return reference.of(atomId);
    }
}
