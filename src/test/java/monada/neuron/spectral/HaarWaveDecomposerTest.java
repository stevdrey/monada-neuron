package monada.neuron.spectral;

import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.DecompositionResult.DecompositionRejected;
import org.junit.jupiter.api.Test;

import java.util.function.IntToDoubleFunction;

import static monada.neuron.spectral.SpectralFixtures.PROVENANCE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract section 12 fixtures owned by #124 (P9, N4), scale separation on temporal fixtures, the length and energy
 * rejections, energy bookkeeping and determinism of the Haar strategy, with the section 12.4 tolerances.
 */
class HaarWaveDecomposerTest {

    private static final double PI = StrictMath.PI;
    private static final double SQRT_2 = StrictMath.sqrt(2.0);
    private static final double EXACT = 1e-12;
    private static final double PARSEVAL = 1e-9;

    @Test
    void reportsTheHaarBasisAndItsFixedDepth() {
        var decomposer = new HaarWaveDecomposer(3);

        assertEquals(SpectralBasis.HAAR, decomposer.basis());
        assertEquals(new SpectralScale(3), decomposer.scale());
        assertThrows(IllegalArgumentException.class, () -> new HaarWaveDecomposer(0));
        assertThrows(IllegalArgumentException.class,
                () -> new HaarWaveDecomposer(SpectralScale.MAX_HAAR_LEVELS + 1));
    }

    @Test
    void p9ExactValuesOrderAndEnergy() {
        var wave = new SampledWave(new double[] {4.0, 2.0, 5.0, 5.0}, 4.0, SampleUnit.HERTZ, PROVENANCE);
        var pattern = decompose(new HaarWaveDecomposer(2), wave);

        assertAll(
                () -> assertArrayEquals(new int[] {0, 1, 1, 2}, pattern.levels()),
                () -> assertArrayEquals(new int[] {0, 0, 1, 0}, pattern.indices()),
                () -> assertEquals(8.0, coefficient(pattern, 0, 0), EXACT),
                () -> assertEquals(SQRT_2, coefficient(pattern, 1, 0), EXACT),
                () -> assertEquals(0.0, coefficient(pattern, 1, 1), EXACT),
                () -> assertEquals(-2.0, coefficient(pattern, 2, 0), EXACT),
                () -> assertEquals(PI, pattern.phase(3)),
                () -> assertEquals(LocatorUnit.SAMPLE_POSITION, pattern.locatorUnit()),
                () -> assertArrayEquals(new double[] {0.0, 0.0, 2.0, 0.0}, locators(pattern)),
                () -> assertEquals(70.0, pattern.sourceEnergy()),
                () -> assertEquals(70.0, pattern.retainedEnergy(), EXACT),
                () -> assertEquals(64.0, pattern.levelEnergy(0), EXACT),
                () -> assertEquals(2.0, pattern.levelEnergy(1), EXACT),
                () -> assertEquals(4.0, pattern.levelEnergy(2), EXACT));
    }

    @Test
    void n4NonPowerOfTwoLengthsAreRejectedNotPadded() {
        var decomposer = new HaarWaveDecomposer(1);

        assertRejected(decomposer, wave(6, n -> n), DecompositionRejectionReason.SAMPLE_COUNT_UNSUPPORTED);
        assertRejected(decomposer, wave(7, n -> n), DecompositionRejectionReason.SAMPLE_COUNT_UNSUPPORTED);
        assertRejected(decomposer, wave(4095, n -> 1.0), DecompositionRejectionReason.SAMPLE_COUNT_UNSUPPORTED);
        assertRejected(decomposer, wave(1, n -> 1.0), DecompositionRejectionReason.SAMPLE_COUNT_UNSUPPORTED);
    }

    @Test
    void depthLargerThanTheWindowIsRejectedNotClamped() {
        assertRejected(new HaarWaveDecomposer(2), wave(2, n -> 1.0),
                DecompositionRejectionReason.SAMPLE_COUNT_UNSUPPORTED);
        assertRejected(new HaarWaveDecomposer(4), wave(8, n -> 1.0),
                DecompositionRejectionReason.SAMPLE_COUNT_UNSUPPORTED);

        var shortest = decompose(new HaarWaveDecomposer(1), wave(2, n -> n == 0 ? 3.0 : 1.0));
        assertEquals(4.0 / SQRT_2, coefficient(shortest, 0, 0), EXACT);
        assertEquals(2.0 / SQRT_2, coefficient(shortest, 1, 0), EXACT);

        var deepest = decompose(new HaarWaveDecomposer(SpectralScale.MAX_HAAR_LEVELS),
                randomWave(SampledWave.MAX_SAMPLES, 7L));
        assertEquals(SampledWave.MAX_SAMPLES, deepest.componentCount());
        assertEquals(1, countAtLevel(deepest, 0));
        assertEquals(1, countAtLevel(deepest, SpectralScale.MAX_HAAR_LEVELS));
        assertEquals(SampledWave.MAX_SAMPLES / 2, countAtLevel(deepest, 1));
    }

    @Test
    void impulseIsLocalizedToOneCoefficientPerScale() {
        int position = 21;
        var pattern = decompose(new HaarWaveDecomposer(6), wave(64, n -> n == position ? 1.0 : 0.0));

        for (int level = 1; level <= 6; level++) {
            int support = position >> level;
            for (int m = 0; m < 64 >> level; m++) {
                double expected = m == support ? StrictMath.pow(2.0, -level / 2.0) : 0.0;
                assertEquals(expected, coefficient(pattern, level, m) * (m == support ? sign(position, level) : 1),
                        EXACT, "level " + level + " index " + m);
            }
            assertTrue(locatorOf(pattern, level, support) <= position
                    && position < locatorOf(pattern, level, support) + (1 << level));
        }
        assertEquals(1.0 / 8.0, coefficient(pattern, 0, 0), EXACT);
        assertEquals(1.0, pattern.retainedEnergy(), EXACT);
    }

    @Test
    void alignedStepOnlyReachesTheCoarsestScale() {
        var pattern = decompose(new HaarWaveDecomposer(6), wave(64, n -> n < 32 ? 0.0 : 1.0));

        for (int level = 1; level <= 5; level++) {
            assertEquals(0.0, pattern.levelEnergy(level), "level " + level);
        }
        assertEquals(-4.0, coefficient(pattern, 6, 0), EXACT);
        assertEquals(4.0, coefficient(pattern, 0, 0), EXACT);
        assertEquals(16.0, pattern.levelEnergy(6), EXACT);
        assertEquals(32.0, pattern.sourceEnergy());
    }

    @Test
    void misalignedStepIsLocalizedAtItsEdgeOnEveryScale() {
        int edge = 21;
        var pattern = decompose(new HaarWaveDecomposer(6), wave(64, n -> n < edge ? 0.0 : 1.0));

        for (int level = 1; level <= 6; level++) {
            for (int m = 0; m < 64 >> level; m++) {
                boolean straddles = m == edge >> level;
                assertEquals(straddles, coefficient(pattern, level, m) != 0.0, "level " + level + " index " + m);
            }
        }
    }

    @Test
    void alternatingSignalLivesOnlyAtTheFinestScale() {
        var pattern = decompose(new HaarWaveDecomposer(6), wave(64, n -> n % 2 == 0 ? 1.0 : -1.0));

        assertEquals(64.0, pattern.levelEnergy(1), EXACT);
        for (int m = 0; m < 32; m++) {
            assertEquals(SQRT_2, coefficient(pattern, 1, m), EXACT);
        }
        for (int level = 2; level <= 6; level++) {
            assertEquals(0.0, pattern.levelEnergy(level), "level " + level);
        }
        assertEquals(0.0, pattern.levelEnergy(0));
    }

    @Test
    void squareWaveOfPeriodEightLivesOnlyAtLevelThree() {
        var pattern = decompose(new HaarWaveDecomposer(6), wave(64, n -> n % 8 < 4 ? 1.0 : -1.0));

        for (int level = 0; level <= 6; level++) {
            assertEquals(level == 3 ? 64.0 : 0.0, pattern.levelEnergy(level), EXACT, "level " + level);
        }
    }

    @Test
    void mixedTransientAndPeriodicContentSeparateByScale() {
        int position = 40;
        double spike = 5.0;
        var pattern = decompose(new HaarWaveDecomposer(6),
                wave(64, n -> (n % 8 < 4 ? 1.0 : -1.0) + (n == position ? spike : 0.0)));

        double impulseEnergy = spike * spike;
        for (int level = 1; level <= 6; level++) {
            if (level != 3) {
                assertEquals(impulseEnergy / (1 << level), pattern.levelEnergy(level), EXACT, "level " + level);
            }
        }
        assertEquals(impulseEnergy / 64.0, pattern.levelEnergy(0), EXACT);
        assertTrue(pattern.levelEnergy(3) > 64.0, "the periodic part dominates level 3");
        assertEquals(position, locatorOf(pattern, 1, position >> 1));
        assertEquals(pattern.sourceEnergy(), sumOfLevelEnergies(pattern), PARSEVAL * pattern.sourceEnergy());
    }

    @Test
    void silenceRetainsNoEnergyAndDefinesNoPhase() {
        var pattern = decompose(new HaarWaveDecomposer(3), wave(8, n -> 0.0));

        assertEquals(8, pattern.componentCount());
        assertEquals(0.0, pattern.sourceEnergy());
        assertEquals(0.0, pattern.retainedEnergy());
        for (int i = 0; i < pattern.componentCount(); i++) {
            assertEquals(0.0, pattern.magnitude(i));
            assertEquals(0.0, pattern.phase(i));
            assertFalse(pattern.phaseDefined(i));
        }
        for (int level = 0; level <= 3; level++) {
            assertEquals(0.0, pattern.levelEnergy(level));
        }
    }

    @Test
    void energyIsPreservedOnSeededRandomWindows() {
        for (int n : new int[] {8, 256, 4096}) {
            int depth = Integer.numberOfTrailingZeros(n);
            for (int levels : new int[] {1, depth}) {
                var wave = randomWave(n, 42L + n);
                var pattern = decompose(new HaarWaveDecomposer(levels), wave);
                double energy = 0.0;
                for (int i = 0; i < n; i++) {
                    energy += wave.sample(i) * wave.sample(i);
                }
                assertEquals(energy, pattern.sourceEnergy(), PARSEVAL * energy, "N=" + n);
                assertEquals(energy, pattern.retainedEnergy(), PARSEVAL * energy, "N=" + n + " L=" + levels);
                assertEquals(energy, sumOfLevelEnergies(pattern), PARSEVAL * energy, "N=" + n + " L=" + levels);
            }
        }
    }

    @Test
    void patternCarriesWhatAnInverseNeedsAndRoundTrips() {
        var provenance = new WaveProvenance("encoder.trace", 3, "trace-17", 4096L);
        for (int n : new int[] {8, 256, 4096}) {
            for (int levels : new int[] {1, 3, Integer.numberOfTrailingZeros(n)}) {
                var random = randomWave(n, 9L + n);
                var wave = new SampledWave(random.samples(), 32.0, SampleUnit.HERTZ, provenance);
                var pattern = decompose(new HaarWaveDecomposer(levels), wave);

                assertEquals(new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(levels), n, 32.0,
                        SampleUnit.HERTZ), pattern.frame());
                assertEquals(provenance, pattern.provenance());
                assertEquals(pattern.frame().componentCapacity(), pattern.componentCount());

                double[] restored = assertInstanceOf(SynthesisResult.Reconstructed.class,
                        new WaveSynthesizer().synthesize(pattern)).samples();
                double tolerance = 1e-9 * Math.max(1.0, SpectralArithmetic.peakMagnitude(wave.samples()));
                for (int i = 0; i < n; i++) {
                    assertEquals(wave.sample(i), restored[i], tolerance, "sample " + i + " of N=" + n);
                }
            }
        }
    }

    @Test
    void sampleRateOnlyTravelsIntoTheFrame() {
        var decomposer = new HaarWaveDecomposer(2);
        var huge = decompose(decomposer, new SampledWave(new double[] {1, 2, 3, 4}, 1e308, SampleUnit.HERTZ,
                PROVENANCE));
        var tiny = decompose(decomposer, new SampledWave(new double[] {1, 2, 3, 4}, 1e-307, SampleUnit.HERTZ,
                PROVENANCE));
        var abstractUnit = decompose(decomposer, new SampledWave(new double[] {1, 2, 3, 4}, 1.0,
                SampleUnit.ABSTRACT_PER_INDEX, PROVENANCE));

        assertArrayEquals(huge.magnitudes(), tiny.magnitudes());
        assertArrayEquals(huge.magnitudes(), abstractUnit.magnitudes());
        assertArrayEquals(locators(huge), locators(abstractUnit));
        assertEquals(LocatorUnit.SAMPLE_POSITION, abstractUnit.locatorUnit());
    }

    @Test
    void fourierAndWaveletPatternsNeverShareBinSemantics() {
        var wave = wave(8, n -> StrictMath.cos(2 * PI * n / 8.0));
        var dft = assertInstanceOf(Decomposed.class, new DftWaveDecomposer().decompose(wave)).pattern();
        var haar = decompose(new HaarWaveDecomposer(3), wave);

        assertAll(
                () -> assertNotEquals(dft.frame(), haar.frame()),
                () -> assertNotEquals(dft.basis(), haar.basis()),
                () -> assertNotEquals(dft.scale(), haar.scale()),
                () -> assertEquals(LocatorUnit.HERTZ, dft.locatorUnit()),
                () -> assertEquals(LocatorUnit.SAMPLE_POSITION, haar.locatorUnit()),
                () -> assertNotEquals(dft.componentCount(), haar.componentCount()),
                () -> assertNotEquals(dft, haar),
                () -> assertThrows(IllegalArgumentException.class, () -> dft.levelEnergy(1)),
                () -> assertEquals(dft.sourceEnergy(), haar.sourceEnergy()));
    }

    @Test
    void rejectsSourceEnergyOutsideTheRepresentableRange() {
        var decomposer = new HaarWaveDecomposer(1);

        assertRejected(decomposer, new SampledWave(new double[] {1e200, 1.0}, 2.0, SampleUnit.HERTZ, PROVENANCE),
                DecompositionRejectionReason.ENERGY_OUT_OF_RANGE);
        assertRejected(decomposer, new SampledWave(new double[] {1e-160, 0.0}, 2.0, SampleUnit.HERTZ, PROVENANCE),
                DecompositionRejectionReason.ENERGY_OUT_OF_RANGE);
        assertRejected(decomposer, new SampledWave(new double[] {Double.MIN_VALUE, 0.0}, 2.0, SampleUnit.HERTZ,
                PROVENANCE), DecompositionRejectionReason.ENERGY_OUT_OF_RANGE);
    }

    @Test
    void acceptsEnergiesAtBothEndsOfTheNormalRange() {
        var decomposer = new HaarWaveDecomposer(1);
        double atBound = StrictMath.sqrt(SpectralPattern.MAX_SOURCE_ENERGY / 2.0);
        while (2.0 * atBound * atBound > SpectralPattern.MAX_SOURCE_ENERGY) {
            atBound = Math.nextDown(atBound);
        }
        var large = decompose(decomposer, new SampledWave(new double[] {atBound, -atBound}, 2.0, SampleUnit.HERTZ,
                PROVENANCE));
        var small = decompose(decomposer, new SampledWave(new double[] {1e-150, -1e-150}, 2.0, SampleUnit.HERTZ,
                PROVENANCE));

        assertTrue(Double.isFinite(large.retainedEnergy()));
        assertEquals(2.0 * atBound / SQRT_2, large.magnitude(1), Math.ulp(large.magnitude(1)) * 2);
        assertEquals(0.0, large.magnitude(0));
        assertEquals(2e-150 / SQRT_2, small.magnitude(1), 1e-162);
    }

    @Test
    void multiLevelWindowsAtTheExactEnergyBoundsAreAcceptedAndJustOutsideRejected() {
        var decomposer = new HaarWaveDecomposer(2);
        // [x, x, x, -x] has energy 4x²; at L = 2 it decomposes to approximation x, level 2 detail x, level 1 [0, √2·x].
        double lowest = Math.scalb(1.0, -512);  // 4x² = 2^-1022 = Double.MIN_NORMAL exactly
        var small = decompose(decomposer, boundaryWave(lowest));

        assertEquals(Double.MIN_NORMAL, small.sourceEnergy());
        assertEquals(small.sourceEnergy(), small.retainedEnergy(),
                SpectralPattern.ENERGY_TOLERANCE * Double.MIN_NORMAL);
        assertEquals(lowest, coefficient(small, 0, 0), Math.ulp(lowest) * 2);
        assertEquals(lowest, coefficient(small, 2, 0), Math.ulp(lowest) * 2);
        assertRejected(decomposer, boundaryWave(Math.nextDown(lowest)),
                DecompositionRejectionReason.ENERGY_OUT_OF_RANGE);

        double highest = StrictMath.sqrt(SpectralPattern.MAX_SOURCE_ENERGY / 4.0);
        while (SpectralArithmetic.sumOfSquares(boundaryWave(highest).samples(), highest)
                > SpectralPattern.MAX_SOURCE_ENERGY) {
            highest = Math.nextDown(highest);
        }
        var large = decompose(decomposer, boundaryWave(highest));

        assertTrue(Double.isFinite(large.retainedEnergy()));
        assertEquals(large.sourceEnergy(), large.retainedEnergy(),
                SpectralPattern.ENERGY_TOLERANCE * large.sourceEnergy());
        assertEquals(highest, coefficient(large, 0, 0), Math.ulp(highest) * 2);
        double above = highest;
        while (SpectralArithmetic.sumOfSquares(boundaryWave(above).samples(), above)
                <= SpectralPattern.MAX_SOURCE_ENERGY) {
            above = Math.nextUp(above);
        }
        assertRejected(decomposer, boundaryWave(above), DecompositionRejectionReason.ENERGY_OUT_OF_RANGE);
    }

    private static SampledWave boundaryWave(double x) {
        return new SampledWave(new double[] {x, x, x, -x}, 4.0, SampleUnit.HERTZ, PROVENANCE);
    }

    @Test
    void extremeMagnitudesNeverEscapeAsExceptions() {
        long state = 0x5EED_0124L;
        for (int trial = 0; trial < 20_000; trial++) {
            int n = 2 << (trial % 5 == 4 ? 5 : trial % 5);  // 2, 4, 8, 16, 64
            int maxDepth = Integer.numberOfTrailingZeros(n);
            int depth = 1 + (trial / 5) % maxDepth;  // every depth up to full, so multi-level passes are covered
            var samples = new double[n];
            for (int i = 0; i < n; i++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                double unit = -1.0 + 2.0 * ((state >>> 11) * 0x1.0p-53);
                int exponent = (trial & 1) == 0 ? 508 + (int) ((state >>> 3) & 7) : -508 - (int) ((state >>> 3) & 7);
                samples[i] = Math.scalb(unit, exponent);
            }
            var wave = new SampledWave(samples, n, SampleUnit.HERTZ, PROVENANCE);
            var result = new HaarWaveDecomposer(depth).decompose(wave);
            if (result instanceof DecompositionRejected rejected) {
                assertEquals(DecompositionRejectionReason.ENERGY_OUT_OF_RANGE, rejected.reason());
            }
        }
    }

    @Test
    void decompositionIsDeterministicAcrossCallsAndInstances() {
        var wave = randomWave(256, 42L);
        var first = decompose(new HaarWaveDecomposer(8), wave);
        var again = decompose(new HaarWaveDecomposer(8), wave);

        assertEquals(first, again);
        assertArrayEquals(bits(first.magnitudes()), bits(again.magnitudes()));
        assertArrayEquals(bits(first.phases()), bits(again.phases()));
        assertEquals(Double.doubleToRawLongBits(first.sourceEnergy()),
                Double.doubleToRawLongBits(again.sourceEnergy()));
    }

    /**
     * Golden values recorded from this reference: a seeded window of {@code N = 256} at full depth must reproduce them
     * bit for bit on any JVM, because the samples and every operation are {@code StrictMath} or IEEE 754 arithmetic
     * in a fixed order.
     */
    @Test
    void goldenSeededWindowIsBitReproducible() {
        var pattern = decompose(new HaarWaveDecomposer(8), randomWave(256, 42L));

        assertEquals(GOLDEN_CHECKSUM, checksum(pattern));
    }

    private static final long GOLDEN_CHECKSUM = -5340479291123720673L;

    private static long checksum(SpectralPattern pattern) {
        long hash = Double.doubleToRawLongBits(pattern.sourceEnergy());
        for (int i = 0; i < pattern.componentCount(); i++) {
            hash = 31 * hash + Double.doubleToRawLongBits(pattern.magnitude(i));
            hash = 31 * hash + Double.doubleToRawLongBits(pattern.phase(i));
        }
        return hash;
    }

    private static double coefficient(SpectralPattern pattern, int level, int index) {
        int i = slot(pattern, level, index);
        return pattern.phase(i) == PI ? -pattern.magnitude(i) : pattern.magnitude(i);
    }

    private static double locatorOf(SpectralPattern pattern, int level, int index) {
        return pattern.locator(slot(pattern, level, index));
    }

    /**
     * Slot of a key in a complete Haar pattern from the canonical layout: level 0 at {@code 0}, level {@code j >= 1} at
     * {@code N/2^L + N - N/2^(j-1)}. The key stored there is asserted, so the layout is checked, not assumed.
     */
    private static int slot(SpectralPattern pattern, int level, int index) {
        int n = pattern.sampleCount();
        int levelStart = level == 0 ? 0 : (n >> pattern.scale().levels()) + n - (n >> (level - 1));
        int i = levelStart + index;
        if (pattern.level(i) != level || pattern.index(i) != index) {
            throw new AssertionError("slot " + i + " holds (" + pattern.level(i) + ", " + pattern.index(i)
                    + "), expected (" + level + ", " + index + ")");
        }
        return i;
    }

    /** Sign of the detail coefficient whose support holds an impulse: + in the first half of the support, − else. */
    private static int sign(int position, int level) {
        return (position >> (level - 1)) % 2 == 0 ? 1 : -1;
    }

    private static int countAtLevel(SpectralPattern pattern, int level) {
        int count = 0;
        for (int i = 0; i < pattern.componentCount(); i++) {
            if (pattern.level(i) == level) {
                count++;
            }
        }
        return count;
    }

    private static double sumOfLevelEnergies(SpectralPattern pattern) {
        double sum = 0.0;
        for (int level = 0; level <= pattern.scale().levels(); level++) {
            sum += pattern.levelEnergy(level);
        }
        return sum;
    }

    private static double[] locators(SpectralPattern pattern) {
        var locators = new double[pattern.componentCount()];
        for (int i = 0; i < locators.length; i++) {
            locators[i] = pattern.locator(i);
        }
        return locators;
    }

    private static SpectralPattern decompose(HaarWaveDecomposer decomposer, SampledWave wave) {
        return assertInstanceOf(Decomposed.class, decomposer.decompose(wave)).pattern();
    }

    private static void assertRejected(HaarWaveDecomposer decomposer, SampledWave wave,
                                       DecompositionRejectionReason reason) {
        var first = assertInstanceOf(DecompositionRejected.class, decomposer.decompose(wave));
        assertEquals(reason, first.reason());
        assertEquals(first, decomposer.decompose(wave), "the same wave is rejected the same way");
    }

    private static SampledWave wave(int n, IntToDoubleFunction formula) {
        var samples = new double[n];
        for (int i = 0; i < n; i++) {
            samples[i] = formula.applyAsDouble(i);
        }
        return new SampledWave(samples, n, SampleUnit.HERTZ, PROVENANCE);
    }

    /** Seeded samples in {@code [-10, 10)} from the fixed 64-bit LCG of {@code DftWaveDecomposerTest}. */
    private static SampledWave randomWave(int n, long seed) {
        var samples = new double[n];
        long state = seed;
        for (int i = 0; i < n; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            samples[i] = -10.0 + 20.0 * ((state >>> 11) * 0x1.0p-53);
        }
        return new SampledWave(samples, n, SampleUnit.HERTZ, PROVENANCE);
    }

    private static long[] bits(double[] values) {
        var bits = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Double.doubleToRawLongBits(values[i]);
        }
        return bits;
    }
}
