package monada.neuron.evaluation.jmh;

import monada.neuron.spectral.DecompositionResult;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.HaarWaveDecomposer;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SynthesisResult;
import monada.neuron.spectral.WaveDecomposer;
import monada.neuron.spectral.WaveProvenance;
import monada.neuron.spectral.WaveSynthesizer;
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
 * Scalar baseline of the reference synthesizer (issue #126, ADR 0042): one inverse of the complete pattern of the same
 * seeded window as {@link DftWaveDecomposerBenchmark}, end to end (inverse transform, reconstructed energy and result
 * construction), without error diagnostics. Haar uses full depth {@code L = log2 N}. See
 * {@code docs/benchmarks/wave-decomposer-reference.md}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class WaveSynthesizerBenchmark {

    @Param({"DFT", "HAAR"})
    private SpectralBasis basis;

    @Param({"8", "64", "256", "1024", "4096"})
    private int sampleCount;

    private WaveSynthesizer synthesizer;
    private SpectralPattern pattern;

    @Setup(Level.Trial)
    public void setup() {
        synthesizer = new WaveSynthesizer();
        var random = new SplittableRandom(0x5EED_0123L);
        var samples = new double[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = random.nextDouble(-10.0, 10.0);
        }
        var wave = new SampledWave(samples, sampleCount, SampleUnit.HERTZ,
                new WaveProvenance("benchmark.seeded", 1, "window-0", 0L));
        WaveDecomposer decomposer = switch (basis) {
            case DFT -> new DftWaveDecomposer();
            case HAAR -> new HaarWaveDecomposer(Integer.numberOfTrailingZeros(sampleCount));
        };
        pattern = switch (decomposer.decompose(wave)) {
            case DecompositionResult.Decomposed decomposed -> decomposed.pattern();
            case DecompositionResult.DecompositionRejected rejected ->
                    throw new IllegalStateException(rejected.toString());
        };
    }

    @Benchmark
    public SynthesisResult synthesize() {
        return synthesizer.synthesize(pattern);
    }
}
