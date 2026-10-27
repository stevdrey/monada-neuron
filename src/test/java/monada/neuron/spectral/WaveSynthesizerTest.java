package monada.neuron.spectral;

import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.SynthesisResult.NotInvertible;
import monada.neuron.spectral.SynthesisResult.Reconstructed;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;
import java.util.stream.IntStream;

import static monada.neuron.spectral.SpectralFixtures.PROVENANCE;
import static monada.neuron.spectral.SpectralFixtures.bits;
import static monada.neuron.spectral.SpectralFixtures.p4Wave;
import static monada.neuron.spectral.SpectralFixtures.randomWave;
import static monada.neuron.spectral.SpectralFixtures.samples;
import static monada.neuron.spectral.SpectralFixtures.wave8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate G1s fixtures owned by #126 (P13, P14, N8) and the synthesis rules of contract section 9, with the section 12.4
 * round-trip tolerance {@code max |x̂ − x| <= 1e-9 · max(1, max |x|)}.
 */
class WaveSynthesizerTest {

    private static final double PI = StrictMath.PI;
    private static final double EXACT = 1e-12;
    private static final double ROUND_TRIP = 1e-9;
    private static final double PARSEVAL = 1e-9;

    private final WaveSynthesizer synthesizer = new WaveSynthesizer();
    private final DftWaveDecomposer dft = new DftWaveDecomposer();

    @Test
    void p13DftFixturesRoundTripExactly() {
        assertDftRoundTrip(wave8(n -> 3.0));                                                      // P1
        assertDftRoundTrip(wave8(n -> StrictMath.cos(2 * PI * n / 8.0)));                        // P2
        assertDftRoundTrip(wave8(n -> StrictMath.sin(2 * PI * n / 8.0)));                        // P3
        assertDftRoundTrip(p4Wave());                                                             // P4
        assertDftRoundTrip(wave8(n -> n % 2 == 0 ? 1.0 : -1.0));                                  // P5
        assertDftRoundTrip(new SampledWave(samples(7, n -> StrictMath.cos(2 * PI * 2 * n / 7.0)), 7.0,
                SampleUnit.HERTZ, PROVENANCE));                                                   // P8
        assertDftRoundTrip(wave8(n -> n == 0 ? 1.0 : 0.0));                                       // impulse
        assertDftRoundTrip(wave8(n -> n == 5 ? -2.5 : 0.0));                                      // shifted impulse
        assertDftRoundTrip(wave8(n -> 0.0));                                                      // silence
        assertDftRoundTrip(new SampledWave(new double[] {-4.0}, 1.0, SampleUnit.ABSTRACT_PER_INDEX, PROVENANCE));
        assertDftRoundTrip(new SampledWave(new double[] {1.0, 3.0}, 2.0, SampleUnit.HERTZ, PROVENANCE));
    }

    @Test
    void p13SeededDftWindowsRoundTripExactly() {
        for (int n : new int[] {8, 256, 4096}) {
            assertDftRoundTrip(randomWave(n, 13L + n));
        }
        for (int n : new int[] {7, 255}) {
            assertDftRoundTrip(randomWave(n, 31L + n));
        }
    }

    @Test
    void silenceReconstructsSilenceWithFullFraction() {
        var wave = wave8(n -> 0.0);
        var result = reconstructed(synthesizer.synthesize(dftOf(wave), wave));

        assertEquals(SynthesisStatus.EXACT, result.status());
        assertArrayEquals(new double[8], result.samples());
        assertEquals(1.0, result.retainedEnergyFraction());
        assertEquals(0.0, result.reconstructedEnergy());
        assertEquals(0.0, result.maxAbsoluteError().orElseThrow());
        assertEquals(0.0, result.rmsError().orElseThrow());
        assertEquals(0.0, result.relativeEnergyError().orElseThrow());
    }

    @Test
    void n8TruncatedDftIsLossyWithMeasuredErrors() {
        var wave = p4Wave();
        var truncated = drop(dftOf(wave), i -> i == 1);

        var result = reconstructed(synthesizer.synthesize(truncated, wave));

        assertEquals(SynthesisStatus.LOSSY, result.status());
        assertEquals(0.2, result.retainedEnergyFraction(), EXACT);
        // The residual is −cos(2πn/8): peak 1, RMS 1/√2, energy 1 of 5 retained.
        assertEquals(1.0, result.maxAbsoluteError().orElseThrow(), EXACT);
        assertEquals(1.0 / StrictMath.sqrt(2.0), result.rmsError().orElseThrow(), EXACT);
        assertEquals(0.8, result.relativeEnergyError().orElseThrow(), EXACT);
        assertEquals(1.0, result.reconstructedEnergy(), EXACT);
        for (int n = 0; n < 8; n++) {
            assertEquals(0.5 * StrictMath.cos(2 * PI * 3 * n / 8.0 + PI / 4), result.sample(n), EXACT);
        }
    }

    @Test
    void droppingAnySingleComponentIsNeverExact() {
        var dftPattern = dftOf(randomWave(16, 5L));
        for (int dropped = 0; dropped < dftPattern.componentCount(); dropped++) {
            int target = dropped;
            var result = synthesizer.synthesize(drop(dftPattern, i -> i == target));
            assertEquals(SynthesisStatus.LOSSY, result.status(), "DFT without component " + dropped);
        }
        var haarPattern = haarOf(new HaarWaveDecomposer(3), randomWave(16, 6L));
        int approximationCount = 16 >> 3;
        for (int dropped = 0; dropped < haarPattern.componentCount(); dropped++) {
            int target = dropped;
            var result = synthesizer.synthesize(drop(haarPattern, i -> i == target));
            var expected = dropped < approximationCount ? SynthesisStatus.NOT_INVERTIBLE : SynthesisStatus.LOSSY;
            assertEquals(expected, result.status(), "Haar without component " + dropped);
        }
    }

    @Test
    void emptyDftPatternIsLossySilence() {
        var wave = p4Wave();
        var empty = drop(dftOf(wave), i -> true);

        var result = reconstructed(synthesizer.synthesize(empty, wave));

        assertEquals(SynthesisStatus.LOSSY, result.status());
        assertEquals(0.0, result.retainedEnergyFraction());
        assertArrayEquals(new double[8], result.samples());
        assertEquals(1.0, result.relativeEnergyError().orElseThrow());
    }

    @Test
    void p14HaarWindowsRoundTripExactly() {
        for (int n : new int[] {8, 256, 4096}) {
            for (int levels : new int[] {1, 3, Integer.numberOfTrailingZeros(n)}) {
                var wave = randomWave(n, 14L + n + levels);
                var pattern = haarOf(new HaarWaveDecomposer(levels), wave);

                var result = reconstructed(synthesizer.synthesize(pattern, wave));

                assertEquals(SynthesisStatus.EXACT, result.status(), "N=" + n + " L=" + levels);
                assertRoundTrip(wave, result);
            }
        }
    }

    @Test
    void p9HaarPatternReconstructsItsSamples() {
        var result = reconstructed(synthesizer.synthesize(SpectralFixtures.p9()));

        assertEquals(SynthesisStatus.EXACT, result.status());
        assertArrayEquals(new double[] {4, 2, 5, 5}, result.samples(), EXACT);
        assertEquals(70.0, result.reconstructedEnergy(), 70.0 * PARSEVAL);
        assertFalse(result.maxAbsoluteError().isPresent());
    }

    @Test
    void thresholdedHaarDetailsAreLossy() {
        var wave = randomWave(64, 21L);
        var pattern = haarOf(new HaarWaveDecomposer(4), wave);
        var thresholded = drop(pattern, i -> pattern.level(i) > 0 && pattern.magnitude(i) < 3.0);
        assertTrue(thresholded.componentCount() < pattern.componentCount(), "the fixture drops details");

        var result = reconstructed(synthesizer.synthesize(thresholded, wave));

        assertEquals(SynthesisStatus.LOSSY, result.status());
        double fraction = thresholded.retainedEnergy() / thresholded.sourceEnergy();
        assertEquals(fraction, result.retainedEnergyFraction());
        assertTrue(fraction < 1.0);
        assertTrue(result.maxAbsoluteError().orElseThrow() > 0.0);
        assertTrue(result.rmsError().orElseThrow() > 0.0);
        assertTrue(result.relativeEnergyError().orElseThrow() > 0.0);
        assertEquals(thresholded.retainedEnergy(), result.reconstructedEnergy(),
                PARSEVAL * thresholded.retainedEnergy(), "orthonormal: the retained energy is reconstructed");
    }

    @Test
    void haarWithoutItsApproximationIsNotInvertible() {
        var wave = randomWave(32, 8L);
        var pattern = haarOf(new HaarWaveDecomposer(3), wave);
        var withoutOne = drop(pattern, i -> i == 2);
        var withoutAll = drop(pattern, i -> pattern.level(i) == 0);

        for (var incomplete : new SpectralPattern[] {withoutOne, withoutAll}) {
            var result = assertInstanceOf(NotInvertible.class, synthesizer.synthesize(incomplete, wave));
            assertEquals(NotInvertibleReason.MISSING_APPROXIMATION, result.reason());
            assertEquals(SynthesisStatus.NOT_INVERTIBLE, result.status());
            assertEquals(incomplete.retainedEnergy() / incomplete.sourceEnergy(), result.retainedEnergyFraction());
            assertEquals(result, synthesizer.synthesize(incomplete), "refusal does not depend on the original");
        }
    }

    @Test
    void errorsAreAbsentWithoutTheOriginal() {
        var result = reconstructed(synthesizer.synthesize(dftOf(p4Wave())));

        assertEquals(SynthesisStatus.EXACT, result.status());
        assertTrue(result.maxAbsoluteError().isEmpty());
        assertTrue(result.rmsError().isEmpty());
        assertTrue(result.relativeEnergyError().isEmpty());
    }

    @Test
    void synthesisUsesEveryStoredPhase() {
        var frame = SpectralFixtures.DFT_8;
        var tinyInPhase = SpectralPattern.of(frame, 4.0, PROVENANCE, new int[] {0, 0}, new int[] {1, 2},
                new double[] {1.0, 1e-13}, new double[] {0.0, 0.0});
        var tinyShifted = SpectralPattern.of(frame, 4.0, PROVENANCE, new int[] {0, 0}, new int[] {1, 2},
                new double[] {1.0, 1e-13}, new double[] {0.0, PI / 2});
        assertFalse(tinyShifted.phaseDefined(1), "the fixture phase is flagged undefined");

        var inPhase = reconstructed(synthesizer.synthesize(tinyInPhase));
        var shifted = reconstructed(synthesizer.synthesize(tinyShifted));

        assertFalse(Arrays.equals(bits(inPhase.samples()), bits(shifted.samples())),
                "an undefined phase still changes the synthesized samples");
    }

    @Test
    void synthesisIsBitReproducible() {
        var wave = randomWave(256, 42L);
        var dftPattern = dftOf(wave);
        var haarPattern = haarOf(new HaarWaveDecomposer(8), wave);
        for (var pattern : new SpectralPattern[] {dftPattern, haarPattern}) {
            var first = synthesizer.synthesize(pattern, wave);
            assertEquals(first, synthesizer.synthesize(pattern, wave));
            assertEquals(first, new WaveSynthesizer().synthesize(pattern, wave));
        }
    }

    @Test
    void goldenSeededReconstructionsAreBitReproducible() {
        var wave = randomWave(256, 42L);

        assertEquals(GOLDEN_DFT, checksum(reconstructed(synthesizer.synthesize(dftOf(wave)))));
        assertEquals(GOLDEN_HAAR, checksum(reconstructed(synthesizer.synthesize(
                haarOf(new HaarWaveDecomposer(8), wave)))));
    }

    private static final long GOLDEN_DFT = -8906332664969418357L;
    private static final long GOLDEN_HAAR = 5824922934004955983L;

    @Test
    void extremeMagnitudesStayFinite() {
        double huge = 1e153;
        double tiny = 1e-154;
        var waves = new SampledWave[] {
                wave8(n -> n % 3 == 0 ? huge : -huge / (n + 1)),
                wave8(n -> n % 2 == 0 ? tiny : -tiny * (n + 1)),
                wave8(n -> n == 3 ? huge : tiny),
        };
        for (var wave : waves) {
            for (var pattern : new SpectralPattern[] {dftOf(wave), haarOf(new HaarWaveDecomposer(3), wave)}) {
                var exact = reconstructed(synthesizer.synthesize(pattern, wave));
                assertEquals(SynthesisStatus.EXACT, exact.status());
                assertRoundTrip(wave, exact);
                assertTrue(Double.isFinite(exact.reconstructedEnergy()));

                var lossy = reconstructed(synthesizer.synthesize(drop(pattern, i -> i == pattern.componentCount() - 1),
                        wave));
                assertTrue(Double.isFinite(lossy.rmsError().orElseThrow()));
                assertTrue(Double.isFinite(lossy.relativeEnergyError().orElseThrow()));
            }
        }
    }

    @Test
    void anOriginalThatIsNotTheDecomposedWaveIsRejected() {
        var wave = p4Wave();
        var pattern = dftOf(wave);
        var x = wave.samples();
        var doubled = samples(8, n -> 2 * x[n]);

        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize(pattern,
                new SampledWave(doubled, 8.0, SampleUnit.HERTZ, PROVENANCE)));
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize(pattern,
                new SampledWave(x, 16.0, SampleUnit.HERTZ, PROVENANCE)));
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize(pattern,
                new SampledWave(x, 8.0, SampleUnit.HERTZ, new WaveProvenance("encoder.other", 1, "window-0", 0L))));
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize(pattern,
                new SampledWave(Arrays.copyOf(x, 7), 8.0, SampleUnit.HERTZ, PROVENANCE)));

        var abstractWave = new SampledWave(x, 1.0, SampleUnit.ABSTRACT_PER_INDEX, PROVENANCE);
        var hertzPattern = dftOf(new SampledWave(x, 1.0, SampleUnit.HERTZ, PROVENANCE));
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize(hertzPattern, abstractWave));

        assertThrows(NullPointerException.class, () -> synthesizer.synthesize(null));
        assertThrows(NullPointerException.class, () -> synthesizer.synthesize(pattern, null));
        assertThrows(NullPointerException.class, () -> synthesizer.synthesize(null, wave));
    }

    @Test
    void silenceOfTheOriginalMustMatchThePattern() {
        var tinyPattern = SpectralPattern.of(SpectralFixtures.DFT_8, 8 * Double.MIN_VALUE, PROVENANCE,
                new int[] {0}, new int[] {0}, new double[] {Double.MIN_VALUE}, new double[] {0.0});
        var silent = wave8(n -> 0.0);
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize(tinyPattern, silent),
                "a silent original is not the source of a pattern with nonzero magnitudes");

        var silentPattern = dftOf(silent);
        var tinyWave = wave8(n -> n == 0 ? Double.MIN_VALUE : 0.0);
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize(silentPattern, tinyWave),
                "a silent pattern is not the decomposition of a nonzero original");
    }

    @Test
    void exactReportsTheFullFractionAndRmsNeverExceedsThePeak() {
        for (int n : new int[] {8, 255, 256}) {
            var wave = randomWave(n, 77L + n);
            var result = reconstructed(synthesizer.synthesize(dftOf(wave), wave));
            assertEquals(1.0, result.retainedEnergyFraction());
            assertTrue(result.rmsError().orElseThrow() <= result.maxAbsoluteError().orElseThrow());
        }
        var constant = wave8(n -> 3.0);
        var lossy = reconstructed(synthesizer.synthesize(drop(dftOf(constant), i -> i == 0), constant));
        double peak = lossy.maxAbsoluteError().orElseThrow();
        assertEquals(3.0, peak, EXACT);
        assertTrue(lossy.rmsError().orElseThrow() <= peak, "equal residuals: RMS reaches the peak, never above it");
        assertEquals(3.0, lossy.rmsError().orElseThrow(), EXACT);
    }

    private void assertDftRoundTrip(SampledWave wave) {
        var pattern = dftOf(wave);
        var result = reconstructed(synthesizer.synthesize(pattern, wave));

        assertEquals(SynthesisStatus.EXACT, result.status(), "N=" + wave.sampleCount());
        assertEquals(1.0, result.retainedEnergyFraction(), PARSEVAL);
        assertRoundTrip(wave, result);
    }

    private static void assertRoundTrip(SampledWave wave, Reconstructed result) {
        double[] x = wave.samples();
        double tolerance = ROUND_TRIP * Math.max(1.0, SpectralArithmetic.peakMagnitude(x));
        assertEquals(x.length, result.sampleCount());
        for (int i = 0; i < x.length; i++) {
            assertEquals(x[i], result.sample(i), tolerance, "sample " + i + " of N=" + x.length);
        }
        assertTrue(result.maxAbsoluteError().orElseThrow() <= tolerance);
        assertTrue(result.rmsError().orElseThrow() <= tolerance);
        assertTrue(result.relativeEnergyError().orElseThrow() <= PARSEVAL);
    }

    private static Reconstructed reconstructed(SynthesisResult result) {
        return assertInstanceOf(Reconstructed.class, result);
    }

    private SpectralPattern dftOf(SampledWave wave) {
        return assertInstanceOf(Decomposed.class, dft.decompose(wave)).pattern();
    }

    private static SpectralPattern haarOf(HaarWaveDecomposer decomposer, SampledWave wave) {
        return assertInstanceOf(Decomposed.class, decomposer.decompose(wave)).pattern();
    }

    /** Returns the pattern without the components whose position matches, as top-K or thresholding would. */
    private static SpectralPattern drop(SpectralPattern pattern, IntPredicate dropped) {
        int[] keep = IntStream.range(0, pattern.componentCount()).filter(dropped.negate())
                .toArray();
        var levels = new int[keep.length];
        var indices = new int[keep.length];
        var magnitudes = new double[keep.length];
        var phases = new double[keep.length];
        for (int j = 0; j < keep.length; j++) {
            levels[j] = pattern.level(keep[j]);
            indices[j] = pattern.index(keep[j]);
            magnitudes[j] = pattern.magnitude(keep[j]);
            phases[j] = pattern.phase(keep[j]);
        }
        return SpectralPattern.of(pattern.frame(), pattern.sourceEnergy(), pattern.provenance(), levels, indices,
                magnitudes, phases);
    }

    private static long checksum(Reconstructed result) {
        long hash = Double.doubleToRawLongBits(result.reconstructedEnergy());
        for (int i = 0; i < result.sampleCount(); i++) {
            hash = 31 * hash + Double.doubleToRawLongBits(result.sample(i));
        }
        return hash;
    }
}
