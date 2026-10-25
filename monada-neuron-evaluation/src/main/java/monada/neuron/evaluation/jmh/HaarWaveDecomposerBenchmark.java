package monada.neuron.evaluation.jmh;

import monada.neuron.spectral.DecompositionResult;
import monada.neuron.spectral.HaarWaveDecomposer;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.WaveProvenance;
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

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Scalar baseline of the Haar multiresolution decomposer (issue #124, ADR 0037): one full-depth decomposition
 * ({@code L = log2 N}) of the same seeded window as {@link DftWaveDecomposerBenchmark}, end to end (energy, the O(N)
 * level passes and pattern construction). See {@code docs/benchmarks/wave-decomposer-reference.md}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class HaarWaveDecomposerBenchmark {

    @Param({"8", "64", "256", "1024", "4096"})
    private int sampleCount;

    private HaarWaveDecomposer decomposer;
    private SampledWave wave;

    @Setup(Level.Trial)
    public void setup() {
        decomposer = new HaarWaveDecomposer(Integer.numberOfTrailingZeros(sampleCount));
        var random = new SplittableRandom(0x5EED_0123L);
        var samples = new double[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = random.nextDouble(-10.0, 10.0);
        }
        wave = new SampledWave(samples, sampleCount, SampleUnit.HERTZ,
                new WaveProvenance("benchmark.seeded", 1, "window-0", 0L));
    }

    @Benchmark
    public DecompositionResult decompose() {
        return decomposer.decompose(wave);
    }
}
