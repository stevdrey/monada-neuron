package monada.neuron.evaluation.jmh;

import monada.neuron.resonance.ComparisonResult;
import monada.neuron.resonance.SpectralResonanceDynamics;
import monada.neuron.spectral.DecompositionResult;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralPattern;
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

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Scalar reference cost of one {@link SpectralResonanceDynamics#compare} (issue #125, ADR 0041), end to end: the
 * compatibility check, the peak and norm scans, the two-pointer band merge and the result construction (band records,
 * list copy and validation). Patterns come from seeded {@code N}-sample windows in {@code [-10, 10]}, built once per
 * trial. See {@code docs/benchmarks/spectral-resonance-h2.md}.
 *
 * <ul>
 *   <li>{@code DENSE}: two complete patterns, every bin shared.</li>
 *   <li>{@code SPARSE_DENSE}: every tenth bin of one window against a complete pattern.</li>
 *   <li>{@code SPARSE_DISJOINT}: bins {@code k ≡ 0} against {@code k ≡ 5 (mod 10)}: no band is shared.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class SpectralResonanceDynamicsBenchmark {

    @Param({"8", "256", "4096"})
    private int sampleCount;

    @Param({"DENSE", "SPARSE_DENSE", "SPARSE_DISJOINT"})
    private String mixture;

    private SpectralResonanceDynamics dynamics;
    private SpectralPattern first;
    private SpectralPattern second;

    @Setup(Level.Trial)
    public void setup() {
        dynamics = new SpectralResonanceDynamics();
        var random = new SplittableRandom(0x5EED_0125L);
        SpectralPattern a = decompose(random);
        SpectralPattern b = decompose(random);
        switch (mixture) {
            case "DENSE" -> {
                first = a;
                second = b;
            }
            case "SPARSE_DENSE" -> {
                first = everyTenth(a, 0);
                second = b;
            }
            case "SPARSE_DISJOINT" -> {
                first = everyTenth(a, 0);
                second = everyTenth(b, 5);
            }
            default -> throw new IllegalArgumentException("unknown mixture: " + mixture);
        }
    }

    @Benchmark
    public ComparisonResult compare() {
        return dynamics.compare(first, second);
    }

    private SpectralPattern decompose(SplittableRandom random) {
        var samples = new double[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = random.nextDouble(-10.0, 10.0);
        }
        var wave = new SampledWave(samples, sampleCount, SampleUnit.HERTZ,
                new WaveProvenance("benchmark.seeded", 1, "window-0", 0L));
        return ((DecompositionResult.Decomposed) new DftWaveDecomposer().decompose(wave)).pattern();
    }

    /** Keeps the bins {@code k ≡ offset (mod 10)}; a truncated pattern keeps its source energy. */
    private SpectralPattern everyTenth(SpectralPattern pattern, int offset) {
        int[] indices = new int[pattern.componentCount()];
        double[] magnitudes = new double[indices.length];
        double[] phases = new double[indices.length];
        int count = 0;
        for (int i = 0; i < pattern.componentCount(); i++) {
            if (pattern.index(i) % 10 == offset) {
                indices[count] = pattern.index(i);
                magnitudes[count] = pattern.magnitude(i);
                phases[count] = pattern.phase(i);
                count++;
            }
        }
        return SpectralPattern.of(pattern.frame(), pattern.sourceEnergy(), pattern.provenance(), new int[count],
                Arrays.copyOf(indices, count), Arrays.copyOf(magnitudes, count), Arrays.copyOf(phases, count));
    }
}
