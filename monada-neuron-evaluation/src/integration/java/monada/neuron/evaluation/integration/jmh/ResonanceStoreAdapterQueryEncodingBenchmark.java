package monada.neuron.evaluation.integration.jmh;

import monada.neuron.model.FrequencyState;
import monada.neuron.resonance.adapter.CanonicalSignalQueryEncoder;
import monada.neuron.resonance.adapter.SignalQueryEncoder;
import monada.neuron.signal.Signal;
import monada.neuron.signal.SignalKind;
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

import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * {@code SignalQueryEncoder.encode(Signal)} with the production {@link CanonicalSignalQueryEncoder}
 * (Issue #49). One invocation encodes a batch of {@code signals} Signals (1, the 3 of the integration
 * evaluation, and 8); the result is per batch.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
public class ResonanceStoreAdapterQueryEncodingBenchmark {

    @Param({"1", "3", "8"})
    public int signals;

    private SignalQueryEncoder encoder;
    private Signal[] batch;

    @Setup(Level.Trial)
    public void setUp() {
        encoder = new CanonicalSignalQueryEncoder();
        batch = new Signal[signals];
        for (var index = 0; index < signals; index++) {
            batch[index] = new Signal(
                    SignalKind.OBSERVATION,
                    new FrequencyState(0.5 + 0.01 * index, 100.0 + index, 0.1 * index));
        }
        for (var signal : batch) {
            if (!encoder.encode(signal).equals(encoder.encode(signal)) || encoder.encode(signal).isBlank()) {
                throw new IllegalStateException("Encoder must be deterministic and non-blank");
            }
        }
    }

    @Benchmark
    public void encodeBatch(Blackhole blackhole) {
        for (var signal : batch) {
            blackhole.consume(encoder.encode(signal));
        }
    }
}
