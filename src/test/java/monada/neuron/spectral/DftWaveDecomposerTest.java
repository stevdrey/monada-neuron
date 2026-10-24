package monada.neuron.spectral;

import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.DecompositionResult.DecompositionRejected;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;
import java.util.function.IntToDoubleFunction;

import static monada.neuron.spectral.SpectralFixtures.PROVENANCE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract section 12 fixtures owned by #123 (P1 to P8, N7) and the decomposer rejections, with the section 12.4
 * tolerances. Unless stated, {@code N = 8}, {@code sampleRate = 8}, unit {@code HERTZ}, so bin {@code k} is {@code k} Hz.
 */
class DftWaveDecomposerTest {

    private static final double PI = StrictMath.PI;
    private static final double EXACT_BIN = 1e-12;
    private static final double PARSEVAL = 1e-9;

    private final DftWaveDecomposer decomposer = new DftWaveDecomposer();

    @Test
    void reportsTheDftBasis() {
        assertEquals(SpectralBasis.DFT, decomposer.basis());
    }

    @Test
    void p1DcIsBinZeroWithPhaseZero() {
        var wave = wave8(n -> 3.0);
        var pattern = decompose(wave);

        assertBin(pattern, 0, 3.0, 0.0);
        assertOtherBinsZero(pattern, wave, 0);
        assertEquals(0.0, pattern.phase(0));
    }

    @Test
    void p2CosineIsBinOneWithPhaseZero() {
        var wave = wave8(n -> StrictMath.cos(2 * PI * n / 8.0));
        var pattern = decompose(wave);

        assertBin(pattern, 1, 1.0, 0.0);
        assertOtherBinsZero(pattern, wave, 1);
    }

    @Test
    void p3SineIsBinOneWithPhaseMinusHalfPi() {
        var wave = wave8(n -> StrictMath.sin(2 * PI * n / 8.0));
        var pattern = decompose(wave);

        assertBin(pattern, 1, 1.0, -PI / 2);
        assertOtherBinsZero(pattern, wave, 1);
    }

    @Test
    void p4MixedTonesKeepTheirAmplitudesAndPhases() {
        var wave = p4Wave();
        var pattern = decompose(wave);

        assertBin(pattern, 1, 1.0, 0.0);
        assertBin(pattern, 3, 0.5, PI / 4);
        assertOtherBinsZero(pattern, wave, 1, 3);
    }

    @Test
    void p5NyquistIsARealBin() {
        var wave = wave8(n -> n % 2 == 0 ? 1.0 : -1.0);
        var pattern = decompose(wave);

        assertEquals(5, pattern.componentCount());
        assertBin(pattern, 4, 1.0, 0.0);
        assertEquals(0.0, pattern.phase(4));
        assertEquals(4.0, pattern.locator(4));
        assertOtherBinsZero(pattern, wave, 4);
    }

    @Test
    void phaseShiftedTonesReportTheirShift() {
        for (double shift : new double[] {PI / 3, 3 * PI / 4, -2 * PI / 3, -PI / 8}) {
            var pattern = decompose(wave8(n -> 2.0 * StrictMath.cos(2 * PI * 2 * n / 8.0 + shift)));

            assertBin(pattern, 2, 2.0, shift);
        }
    }

    @Test
    void negativeRealBinsHavePhasePi() {
        var dc = decompose(wave8(n -> -1.5));
        var nyquist = decompose(wave8(n -> n % 2 == 0 ? -2.0 : 2.0));

        assertEquals(1.5, dc.magnitude(0));
        assertEquals(PI, dc.phase(0));
        assertEquals(2.0, nyquist.magnitude(4));
        assertEquals(PI, nyquist.phase(4));
    }

    @Test
    void p6ParsevalHoldsOnP4AndSeededRandomWindows() {
        assertParseval(p4Wave());
        for (int n : new int[] {256, 4096}) {
            var random = new SplittableRandom(0x5EED_0123L + n);
            var samples = new double[n];
            for (int i = 0; i < n; i++) {
                samples[i] = random.nextDouble(-10.0, 10.0);
            }
            assertParseval(new SampledWave(samples, n, SampleUnit.HERTZ, PROVENANCE));
        }
    }

    @Test
    void p7NonBinCenteredToneLeaksAndStillConservesEnergy() {
        var wave = wave8(n -> StrictMath.cos(2 * PI * 1.5 * n / 8.0));
        var pattern = decompose(wave);

        int spread = 0;
        for (int i = 0; i < pattern.componentCount(); i++) {
            assertNotEquals(1.5, pattern.locator(i));
            if (pattern.magnitude(i) > 0.05) {
                spread++;
            }
        }
        assertTrue(spread >= 3, "leakage spreads the tone over several bins, got " + spread);
        assertTrue(pattern.magnitude(1) < 1.0 && pattern.magnitude(2) < 1.0);
        assertParseval(wave);
    }

    @Test
    void p8OddLengthHasNoNyquistBin() {
        var wave = new SampledWave(samples(7, n -> StrictMath.cos(2 * PI * 2 * n / 7.0)), 7.0, SampleUnit.HERTZ,
                PROVENANCE);
        var pattern = decompose(wave);

        assertEquals(4, pattern.componentCount());
        assertEquals(3, pattern.index(3));
        assertFalse(pattern.frame().isRealValued(0, 3), "the last bin of an odd N is complex");
        assertBin(pattern, 2, 1.0, 0.0);
        assertOtherBinsZero(pattern, wave, 2);
    }

    @Test
    void n7AliasedToneFoldsOntoBinOneAndIsNotRejected() {
        var wave = wave8(n -> StrictMath.cos(2 * PI * 7 * n / 8.0));
        var pattern = decompose(wave);

        assertBin(pattern, 1, 1.0, 0.0);
        assertOtherBinsZero(pattern, wave, 1);
    }

    @Test
    void silenceRetainsNoEnergyAndDefinesNoPhase() {
        var pattern = decompose(wave8(n -> 0.0));

        assertEquals(0.0, pattern.sourceEnergy());
        assertEquals(0.0, pattern.retainedEnergy());
        for (int i = 0; i < pattern.componentCount(); i++) {
            assertEquals(0.0, pattern.magnitude(i));
            assertEquals(0.0, pattern.phase(i));
            assertFalse(pattern.phaseDefined(i));
        }
    }

    @Test
    void shortestWindowsAreRealBinsOnly() {
        var one = decompose(new SampledWave(new double[] {-2.0}, 1.0, SampleUnit.HERTZ, PROVENANCE));
        var two = decompose(new SampledWave(new double[] {1.0, -1.0}, 2.0, SampleUnit.HERTZ, PROVENANCE));

        assertEquals(1, one.componentCount());
        assertEquals(2.0, one.magnitude(0));
        assertEquals(PI, one.phase(0));
        assertArrayEquals(new double[] {0.0, 1.0}, two.magnitudes());
        assertArrayEquals(new double[] {0.0, 0.0}, two.phases());
    }

    @Test
    void abstractUnitLocatesBinsInCyclesPerIndex() {
        var wave = new SampledWave(samples(8, n -> StrictMath.cos(2 * PI * 2 * n / 8.0)), 1.0,
                SampleUnit.ABSTRACT_PER_INDEX, PROVENANCE);
        var pattern = decompose(wave);

        assertEquals(LocatorUnit.CYCLES_PER_INDEX, pattern.locatorUnit());
        assertEquals(0.25, pattern.locator(2));
        assertBin(pattern, 2, 1.0, 0.0);
    }

    @Test
    void keepsTheFrameAndProvenanceOfTheWave() {
        var provenance = new WaveProvenance("encoder.trace", 3, "trace-17", 4096L);
        var wave = new SampledWave(samples(16, n -> n), 32.0, SampleUnit.HERTZ, provenance);
        var pattern = decompose(wave);

        assertAll(
                () -> assertEquals(new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 16, 32.0,
                        SampleUnit.HERTZ), pattern.frame()),
                () -> assertEquals(provenance, pattern.provenance()),
                () -> assertEquals(9, pattern.componentCount()),
                () -> assertEquals(2.0, pattern.locator(1)),
                () -> assertEquals(16.0, pattern.locator(8)));
    }

    @Test
    void rejectsSourceEnergyThatIsNotANormalDouble() {
        assertRejected(new SampledWave(new double[] {1e200, 1.0}, 8.0, SampleUnit.HERTZ, PROVENANCE),
                DecompositionRejectionReason.ENERGY_OUT_OF_RANGE);
        assertRejected(new SampledWave(new double[] {1e-160, 0.0}, 8.0, SampleUnit.HERTZ, PROVENANCE),
                DecompositionRejectionReason.ENERGY_OUT_OF_RANGE);
        assertRejected(new SampledWave(new double[] {Double.MIN_VALUE}, 8.0, SampleUnit.HERTZ, PROVENANCE),
                DecompositionRejectionReason.ENERGY_OUT_OF_RANGE);
    }

    @Test
    void acceptsSmallAndLargeEnergiesInTheNormalRange() {
        var small = decompose(new SampledWave(new double[] {1e-150, -1e-150}, 2.0, SampleUnit.HERTZ, PROVENANCE));
        var large = decompose(new SampledWave(new double[] {1e150, 1e150}, 2.0, SampleUnit.HERTZ, PROVENANCE));

        assertEquals(1e-150, small.magnitude(1), 1e-162);
        assertEquals(1e150, large.magnitude(0), 1e138);
    }

    @Test
    void rejectsSampleRatesTheDftFrameCannotLocate() {
        assertRejected(new SampledWave(new double[8], 1e308, SampleUnit.HERTZ, PROVENANCE),
                DecompositionRejectionReason.SAMPLE_RATE_OUT_OF_RANGE);
        assertRejected(new SampledWave(new double[8], 1e-307, SampleUnit.HERTZ, PROVENANCE),
                DecompositionRejectionReason.SAMPLE_RATE_OUT_OF_RANGE);
    }

    @Test
    void decompositionIsDeterministicAcrossCallsAndInstances() {
        var wave = randomWave(256, 42L);
        var first = decompose(wave);
        var second = new DftWaveDecomposer().decompose(wave);

        var again = assertInstanceOf(Decomposed.class, second).pattern();
        assertEquals(first, again);
        assertArrayEquals(bits(first.magnitudes()), bits(again.magnitudes()));
        assertArrayEquals(bits(first.phases()), bits(again.phases()));
        assertEquals(Double.doubleToRawLongBits(first.sourceEnergy()),
                Double.doubleToRawLongBits(again.sourceEnergy()));
    }

    /**
     * Golden values recorded from this reference: a seeded window of {@code N = 256} must reproduce them bit for bit
     * on any JVM, because every operation is {@code StrictMath} or IEEE 754 arithmetic in a fixed order.
     */
    @Test
    void goldenSeededWindowIsBitReproducible() {
        var pattern = decompose(randomWave(256, 42L));

        assertEquals(GOLDEN_CHECKSUM, checksum(pattern));
    }

    private static final long GOLDEN_CHECKSUM = 3320801901406220982L;

    private static long checksum(SpectralPattern pattern) {
        long hash = Double.doubleToRawLongBits(pattern.sourceEnergy());
        for (int i = 0; i < pattern.componentCount(); i++) {
            hash = 31 * hash + Double.doubleToRawLongBits(pattern.magnitude(i));
            hash = 31 * hash + Double.doubleToRawLongBits(pattern.phase(i));
        }
        return hash;
    }

    private SpectralPattern decompose(SampledWave wave) {
        return assertInstanceOf(Decomposed.class, decomposer.decompose(wave)).pattern();
    }

    private void assertRejected(SampledWave wave, DecompositionRejectionReason reason) {
        var first = assertInstanceOf(DecompositionRejected.class, decomposer.decompose(wave));
        assertEquals(reason, first.reason());
        assertEquals(first, decomposer.decompose(wave), "the same wave is rejected the same way");
    }

    /** Parseval against an independent two-sided DFT that evaluates every angle directly. */
    private void assertParseval(SampledWave wave) {
        var pattern = decompose(wave);
        int n = wave.sampleCount();
        double energy = 0.0;
        for (int i = 0; i < n; i++) {
            energy += wave.sample(i) * wave.sample(i);
        }
        double spectral = 0.0;
        for (int k = 0; k < n; k++) {
            double re = 0.0;
            double im = 0.0;
            for (int i = 0; i < n; i++) {
                double angle = 2 * Math.PI * k * (double) i / n;
                re += wave.sample(i) * Math.cos(angle);
                im -= wave.sample(i) * Math.sin(angle);
            }
            spectral += re * re + im * im;
            if (k <= n / 2) {
                double scale = k == 0 || 2 * k == n ? 1.0 : 2.0;
                double expected = scale * Math.hypot(re, im) / n;
                assertEquals(expected, pattern.magnitude(k), 1e-9 * Math.max(1.0, expected),
                        "bin " + k + " of N=" + n);
            }
        }
        spectral /= n;
        assertEquals(energy, spectral, PARSEVAL * energy, "independent Parseval for N=" + n);
        assertEquals(energy, pattern.sourceEnergy(), PARSEVAL * energy);
        assertEquals(pattern.sourceEnergy(), pattern.retainedEnergy(), PARSEVAL * energy);
    }

    private static void assertBin(SpectralPattern pattern, int k, double magnitude, double phase) {
        assertEquals(k, pattern.index(k));
        assertEquals(magnitude, pattern.magnitude(k), EXACT_BIN, "magnitude of bin " + k);
        assertEquals(phase, pattern.phase(k), EXACT_BIN, "phase of bin " + k);
        assertTrue(pattern.phaseDefined(k));
    }

    private static void assertOtherBinsZero(SpectralPattern pattern, SampledWave wave, int... bins) {
        double absoluteSum = 0.0;
        for (int i = 0; i < wave.sampleCount(); i++) {
            absoluteSum += Math.abs(wave.sample(i));
        }
        double tolerance = EXACT_BIN * Math.max(1.0, absoluteSum);
        outer:
        for (int i = 0; i < pattern.componentCount(); i++) {
            for (int bin : bins) {
                if (pattern.index(i) == bin) {
                    continue outer;
                }
            }
            assertEquals(0.0, pattern.magnitude(i), tolerance, "off-bin magnitude of bin " + pattern.index(i));
        }
    }

    private static SampledWave p4Wave() {
        return wave8(n -> StrictMath.cos(2 * PI * n / 8.0) + 0.5 * StrictMath.cos(2 * PI * 3 * n / 8.0 + PI / 4));
    }

    private static SampledWave wave8(IntToDoubleFunction formula) {
        return new SampledWave(samples(8, formula), 8.0, SampleUnit.HERTZ, PROVENANCE);
    }

    private static SampledWave randomWave(int n, long seed) {
        var random = new SplittableRandom(seed);
        return new SampledWave(samples(n, i -> random.nextDouble(-10.0, 10.0)), n, SampleUnit.HERTZ, PROVENANCE);
    }

    private static double[] samples(int n, IntToDoubleFunction formula) {
        var samples = new double[n];
        for (int i = 0; i < n; i++) {
            samples[i] = formula.applyAsDouble(i);
        }
        return samples;
    }

    private static long[] bits(double[] values) {
        var bits = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Double.doubleToRawLongBits(values[i]);
        }
        return bits;
    }
}
