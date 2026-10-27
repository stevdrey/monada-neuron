package monada.neuron.resonance;

import monada.neuron.resonance.ComparisonResult.Incompatible;
import monada.neuron.resonance.ComparisonResult.SpectralComparison;
import monada.neuron.spectral.DecompositionResult.Decomposed;
import monada.neuron.spectral.DftWaveDecomposer;
import monada.neuron.spectral.SampleUnit;
import monada.neuron.spectral.SampledWave;
import monada.neuron.spectral.SpectralBasis;
import monada.neuron.spectral.SpectralFrame;
import monada.neuron.spectral.SpectralPattern;
import monada.neuron.spectral.SpectralPhase;
import monada.neuron.spectral.SpectralScale;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static monada.neuron.resonance.ResonanceFixtures.DFT_8;
import static monada.neuron.resonance.ResonanceFixtures.HAAR_4_L2;
import static monada.neuron.resonance.ResonanceFixtures.PI;
import static monada.neuron.resonance.ResonanceFixtures.PROVENANCE;
import static monada.neuron.resonance.ResonanceFixtures.p2;
import static monada.neuron.resonance.ResonanceFixtures.p4;
import static monada.neuron.resonance.ResonanceFixtures.p4WithoutBin3;
import static monada.neuron.resonance.ResonanceFixtures.p9;
import static monada.neuron.resonance.ResonanceFixtures.sparse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract section 12 fixtures owned by #125 (P10, P11, P12, P17, N6) and the section 7.3 reference rules. */
class SpectralResonanceDynamicsTest {

    /** Section 12.4 tolerance for resonance values at {@code N <= 64}. */
    private static final double TOLERANCE = 1e-12;

    private final SpectralResonanceDynamics dynamics = new SpectralResonanceDynamics();

    @Test
    void p10IdenticalPatternsAreFullyConstructive() {
        SpectralComparison comparison = compared(p4(), p4());

        assertAll(
                () -> assertEquals(1.0, comparison.aggregate(), TOLERANCE),
                () -> assertEquals(1.0, comparison.coherence(), TOLERANCE),
                () -> assertEquals(5, comparison.bands().size()),
                () -> assertEquals(0, comparison.firstOnlyCount()),
                () -> assertEquals(0, comparison.secondOnlyCount()));
        for (BandContribution band : comparison.bands()) {
            double magnitude = band.firstMagnitude();
            assertEquals(magnitude * magnitude, band.interference(), TOLERANCE);
            assertTrue(band.interference() >= 0.0);
            assertEquals(0.0, band.phaseDifference());
        }
    }

    @Test
    void p11OppositePhaseIsFullyDestructive() {
        SpectralComparison comparison = compared(p2(0.0), p2(PI));
        BandContribution bin1 = comparison.bands().get(1);

        assertAll(
                () -> assertEquals(-1.0, bin1.interference(), TOLERANCE),
                () -> assertEquals(PI, bin1.phaseDifference()),
                () -> assertEquals(1.0, bin1.alignment()),
                () -> assertEquals(0.0, comparison.aggregate(), TOLERANCE),
                () -> assertEquals(1.0, comparison.coherence(), TOLERANCE));
    }

    @Test
    void p12QuadraturePhaseIsNeutralButCoherent() {
        SpectralComparison comparison = compared(p2(0.0), p2(PI / 2));
        BandContribution bin1 = comparison.bands().get(1);

        assertAll(
                () -> assertEquals(0.0, bin1.interference(), TOLERANCE),
                () -> assertEquals(-PI / 2, bin1.phaseDifference(), TOLERANCE),
                () -> assertEquals(0.5, comparison.aggregate(), TOLERANCE),
                () -> assertEquals(1.0, comparison.coherence(), TOLERANCE));
    }

    @Test
    void p17ADroppedBandLowersTheAggregateThroughTheNorm() {
        SpectralComparison comparison = compared(p4(), p4WithoutBin3());

        assertAll(
                () -> assertEquals(1.0, comparison.bands().get(1).interference(), TOLERANCE),
                () -> assertEquals(1, comparison.firstOnlyCount()),
                () -> assertEquals(0, comparison.secondOnlyCount()),
                () -> assertEquals(StrictMath.sqrt(1.25), comparison.firstNorm(), TOLERANCE),
                () -> assertEquals(1.0, comparison.secondNorm(), TOLERANCE),
                () -> assertEquals((1.0 + 1.0 / StrictMath.sqrt(1.25)) / 2.0, comparison.aggregate(), TOLERANCE));
    }

    @Test
    void n6DifferentRatesOrBasesAreIncompatibleNeverInterpolated() {
        var sixteenHertz = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 16.0, SampleUnit.HERTZ);
        var haar8 = new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(3), 8, 8.0, SampleUnit.HERTZ);

        assertReason(IncompatibilityReason.SAMPLE_RATE, p2(0.0), sparse(sixteenHertz, 4.0, new int[] {1},
                new double[] {1.0}, new double[] {0.0}));
        assertReason(IncompatibilityReason.BASIS, p2(0.0), sparse(haar8, 4.0, new int[0], new double[0],
                new double[0]));
    }

    @Test
    void reportsTheFirstDifferingFrameFieldInDeclarationOrder() {
        var empty = new double[0];
        var none = new int[0];
        var haarL1 = new SpectralFrame(SpectralBasis.HAAR, new SpectralScale(1), 4, 4.0, SampleUnit.HERTZ);
        var dft16 = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 16, 8.0, SampleUnit.HERTZ);
        var dft8Abstract = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 1.0,
                SampleUnit.ABSTRACT_PER_INDEX);
        var dft8OneHertz = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 8, 1.0, SampleUnit.HERTZ);

        assertReason(IncompatibilityReason.SCALE, sparse(HAAR_4_L2, 1.0, none, empty, empty),
                sparse(haarL1, 1.0, none, empty, empty));
        assertReason(IncompatibilityReason.SAMPLE_COUNT, sparse(DFT_8, 1.0, none, empty, empty),
                sparse(dft16, 1.0, none, empty, empty));
        assertReason(IncompatibilityReason.UNIT, sparse(dft8OneHertz, 1.0, none, empty, empty),
                sparse(dft8Abstract, 1.0, none, empty, empty));
        // Sample count is checked before the rate, which is checked before the unit.
        assertReason(IncompatibilityReason.SAMPLE_COUNT, sparse(dft16, 1.0, none, empty, empty),
                sparse(dft8Abstract, 1.0, none, empty, empty));
        assertReason(IncompatibilityReason.SAMPLE_RATE, sparse(DFT_8, 1.0, none, empty, empty),
                sparse(dft8Abstract, 1.0, none, empty, empty));
    }

    @Test
    void uncorrelatedPatternsShareNoBandAndSitAtTheMidpoint() {
        SpectralComparison comparison = compared(
                sparse(new int[] {1}, new double[] {1.0}, new double[] {0.0}),
                sparse(new int[] {3}, new double[] {1.0}, new double[] {0.0}));

        assertAll(
                () -> assertEquals(List.of(), comparison.bands()),
                () -> assertEquals(1, comparison.firstOnlyCount()),
                () -> assertEquals(1, comparison.secondOnlyCount()),
                () -> assertEquals(0.5, comparison.aggregate()),
                () -> assertEquals(0.0, comparison.coherence()));
    }

    @Test
    void mismatchedFrequencyInACompletePatternCarriesNoEnergyAcross() {
        // Bin 1 against bin 2: every band is shared, but no shared band has energy in both patterns.
        var bin2 = SpectralPattern.of(DFT_8, 4.0, PROVENANCE, new int[5], new int[] {0, 1, 2, 3, 4},
                new double[] {0.0, 0.0, 1.0, 0.0, 0.0}, new double[5]);
        SpectralComparison comparison = compared(p2(0.0), bin2);

        assertAll(
                () -> assertEquals(5, comparison.bands().size()),
                () -> assertEquals(0.0, comparison.bands().get(1).alignment()),
                () -> assertEquals(0.0, comparison.bands().get(2).alignment()),
                () -> assertEquals(0.5, comparison.aggregate()),
                () -> assertEquals(0.0, comparison.coherence()));
    }

    @Test
    void zeroEnergyOnEitherSideGivesZeroAggregateAndCoherence() {
        var silent = SpectralPattern.of(DFT_8, 0.0, PROVENANCE, new int[5], new int[] {0, 1, 2, 3, 4},
                new double[5], new double[5]);

        for (SpectralComparison comparison : List.of(compared(silent, p4()), compared(p4(), silent),
                compared(silent, silent))) {
            assertAll(
                    () -> assertEquals(0.0, comparison.aggregate()),
                    () -> assertEquals(0.0, comparison.coherence()),
                    () -> assertTrue(comparison.bands().stream().allMatch(b -> b.interference() == 0.0)));
        }
        assertEquals(0.0, compared(silent, silent).bands().get(1).alignment());
    }

    @Test
    void phaseDifferencesWrapAcrossMinusPi() {
        double nearPi = PI - 0.25;
        SpectralComparison comparison = compared(p2(nearPi), p2(-nearPi));
        BandContribution bin1 = comparison.bands().get(1);

        // 2π - 0.5 wraps to -0.5; the interference is that of a 0.5 rad difference, not of an unwrapped one.
        assertEquals(-0.5, bin1.phaseDifference(), TOLERANCE);
        assertEquals(StrictMath.cos(0.5), bin1.interference(), TOLERANCE);
        assertEquals(PI, compared(p2(0.0), p2(PI)).bands().get(1).phaseDifference());
        assertEquals(PI, compared(p2(PI), p2(0.0)).bands().get(1).phaseDifference());
    }

    @Test
    void duplicateBinsCannotReachTheComparison() {
        assertThrows(IllegalArgumentException.class, () -> sparse(new int[] {1, 1}, new double[] {1.0, 1.0},
                new double[] {0.0, 0.0}));
        assertThrows(IllegalArgumentException.class, () -> sparse(new int[] {3, 1}, new double[] {1.0, 1.0},
                new double[] {0.0, 0.0}));
    }

    @Test
    void undefinedPhasesStayInTheInterferenceButLeaveTheCoherence() {
        // In the first pattern bin 2 is below the phase epsilon; in the second, bin 1 is.
        SpectralComparison comparison = compared(
                sparse(new int[] {1, 2}, new double[] {1.0, 1e-13}, new double[] {0.0, PI}),
                sparse(new int[] {1, 2}, new double[] {1e-20, 1.0}, new double[] {0.0, 0.0}));

        assertAll(
                () -> assertFalse(comparison.bands().get(0).phasesDefined()),
                () -> assertFalse(comparison.bands().get(1).phasesDefined()),
                () -> assertEquals(1e-20 - 1e-13, comparison.bands().get(0).interference()
                        + comparison.bands().get(1).interference(), 1e-27),
                () -> assertEquals(0.0, comparison.coherence()));
    }

    @Test
    void staysFiniteAtTheEnergyBound() {
        var frame = new SpectralFrame(SpectralBasis.DFT, SpectralScale.SINGLE, 2, 2.0, SampleUnit.HERTZ);
        double magnitude = StrictMath.sqrt(SpectralPattern.MAX_SOURCE_ENERGY / 2.0);
        var first = sparse(frame, SpectralPattern.MAX_SOURCE_ENERGY, new int[] {0}, new double[] {magnitude},
                new double[] {0.0});
        var second = sparse(frame, SpectralPattern.MAX_SOURCE_ENERGY, new int[] {0}, new double[] {magnitude},
                new double[] {PI});

        SpectralComparison same = compared(first, first);
        SpectralComparison opposite = compared(first, second);
        assertAll(
                () -> assertTrue(Double.isFinite(same.bands().getFirst().interference())),
                () -> assertTrue(Double.isFinite(same.firstNorm())),
                () -> assertEquals(1.0, same.aggregate()),
                () -> assertEquals(0.0, opposite.aggregate()),
                () -> assertTrue(opposite.bands().getFirst().interference() < 0.0));
    }

    @Test
    void scalingKeepsSubnormalEnergiesComparable() {
        // Each product underflows to a subnormal; the scaled sums still normalize exactly.
        SpectralComparison comparison = compared(
                sparse(new int[] {1, 3}, new double[] {1e-160, 5e-161}, new double[] {0.0, PI / 4}),
                sparse(new int[] {1, 3}, new double[] {1e-160, 5e-161}, new double[] {0.0, PI / 4}));

        assertEquals(1.0, comparison.aggregate(), TOLERANCE);
        assertEquals(1.0, comparison.coherence(), TOLERANCE);
    }

    @Test
    void unscaledDiagnosticsUnderflowBelowTheRecomputationLimit() {
        // ‖m1‖·‖m2‖ ≈ 1e-400 is not representable: an unscaled recomputation fails, the scaled one does not.
        SpectralPattern pattern = sparse(new int[] {1}, new double[] {1e-200}, new double[] {0.0});
        SpectralComparison comparison = compared(pattern, pattern);

        assertAll(
                () -> assertEquals(1.0, comparison.aggregate()),
                () -> assertEquals(1.0, comparison.coherence()),
                () -> assertEquals(0.0, comparison.bands().getFirst().interference()),
                () -> assertEquals(0.0, comparison.firstNorm() * comparison.secondNorm()),
                () -> assertRecomputesScaled(comparison));
    }

    @Test
    void coherenceWeightCanUnderflowUnderANormalNormProduct() {
        // Norm product 1e-300 is normal, but the shared band's weight 1e-162 · 1e-162 rounds to 0 unscaled.
        SpectralComparison comparison = compared(
                sparse(DFT_8, 1.0, new int[] {1, 2}, new double[] {1e-150, 1e-162}, new double[] {0.0, 0.0}),
                sparse(DFT_8, 1.0, new int[] {2, 3}, new double[] {1e-162, 1e-150}, new double[] {0.0, 0.0}));
        BandContribution shared = comparison.bands().getFirst();

        assertAll(
                () -> assertTrue(Math.abs(comparison.firstNorm() * comparison.secondNorm()) >= Double.MIN_NORMAL),
                () -> assertTrue(shared.phasesDefined()),
                () -> assertEquals(0.0, shared.firstMagnitude() * shared.secondMagnitude()),
                () -> assertEquals(1.0, comparison.coherence()),
                () -> assertRecomputesScaled(comparison));
    }

    @Test
    void coherenceIsBitSymmetricAtThePiEndpoint() {
        // Δφ = π in both orders; StrictMath.sin(π) ≠ 0 would keep its sign while the π/2 band's sine flips.
        SpectralPattern first = sparse(new int[] {1, 2}, new double[] {1.0, 1.0}, new double[] {PI, PI / 2});
        SpectralPattern second = sparse(new int[] {1, 2}, new double[] {1.0, 1.0}, new double[] {0.0, 0.0});

        SpectralComparison forward = compared(first, second);
        SpectralComparison backward = compared(second, first);
        assertEquals(PI, forward.bands().getFirst().phaseDifference());
        assertEquals(PI, backward.bands().getFirst().phaseDifference());
        assertEquals(Double.doubleToRawLongBits(forward.coherence()), Double.doubleToRawLongBits(backward.coherence()));
        assertEquals(Double.doubleToRawLongBits(forward.aggregate()), Double.doubleToRawLongBits(backward.aggregate()));
        assertEquals(StrictMath.sqrt(0.5), forward.coherence(), TOLERANCE);
    }

    @Test
    void isSymmetricAndBitReproducible() {
        SpectralPattern first = decompose(64, 11L);
        SpectralPattern second = decompose(64, 12L);

        SpectralComparison forward = compared(first, second);
        SpectralComparison backward = compared(second, first);
        assertEquals(forward, compared(first, second));
        assertEquals(Double.doubleToRawLongBits(forward.aggregate()), Double.doubleToRawLongBits(backward.aggregate()));
        assertEquals(Double.doubleToRawLongBits(forward.coherence()), Double.doubleToRawLongBits(backward.coherence()));
        for (int k = 0; k < forward.bands().size(); k++) {
            BandContribution there = forward.bands().get(k);
            BandContribution back = backward.bands().get(k);
            assertEquals(there.interference(), back.interference());
            assertEquals(there.alignment(), back.alignment());
            if (there.phaseDifference() != PI) {
                assertEquals(0.0 - there.phaseDifference(), back.phaseDifference());
            }
        }
    }

    @Test
    void summaryValuesRecomputeFromTheBandDiagnostics() {
        assertRecomputesScaled(compared(decompose(64, 21L), decompose(64, 22L)));
    }

    /**
     * Recomputes both summary values by the documented procedure: each pattern's band magnitudes and norm scaled by
     * {@code 2^-getExponent(norm)}, then the formulas on the scaled magnitudes and the stored phase differences.
     */
    private void assertRecomputesScaled(SpectralComparison comparison) {
        int firstExponent = Math.getExponent(comparison.firstNorm());
        int secondExponent = Math.getExponent(comparison.secondNorm());
        double interference = 0.0;
        double weight = 0.0;
        double cos = 0.0;
        double sin = 0.0;
        for (BandContribution band : comparison.bands()) {
            double w = Math.scalb(band.firstMagnitude(), -firstExponent)
                    * Math.scalb(band.secondMagnitude(), -secondExponent);
            interference += w * StrictMath.cos(band.phaseDifference());
            if (band.phasesDefined()) {
                weight += w;
                cos += w * StrictMath.cos(band.phaseDifference());
                sin += w * StrictMath.sin(band.phaseDifference());
            }
        }
        double norms = Math.scalb(comparison.firstNorm(), -firstExponent)
                * Math.scalb(comparison.secondNorm(), -secondExponent);
        assertEquals((1.0 + interference / norms) / 2.0, comparison.aggregate(), TOLERANCE);
        assertEquals(StrictMath.hypot(cos, sin) / weight, comparison.coherence(), TOLERANCE);
    }

    @Test
    void comparesHaarPatternsByLevelAndPosition() {
        SpectralComparison comparison = compared(p9(), p9());

        assertAll(
                () -> assertEquals(1.0, comparison.aggregate(), TOLERANCE),
                () -> assertEquals(1.0, comparison.coherence(), TOLERANCE),
                () -> assertEquals(List.of(0, 1, 1, 2), comparison.bands().stream().map(BandContribution::level)
                        .toList()),
                () -> assertFalse(comparison.bands().get(2).phasesDefined()));
    }

    @Test
    void phaseRandomizedControlKeepsAlignmentAndLosesInterference() {
        SpectralPattern original = decompose(64, 31L);
        SpectralPattern randomized = withRandomPhases(original, new SplittableRandom(0x125L));

        SpectralComparison comparison = compared(original, randomized);
        assertTrue(comparison.bands().stream().allMatch(b -> b.alignment() == 1.0 || b.firstMagnitude() == 0.0));
        assertTrue(Math.abs(comparison.aggregate() - 0.5) < 0.25, "aggregate " + comparison.aggregate());
        assertTrue(comparison.coherence() < 0.5, "coherence " + comparison.coherence());
        assertEquals(1.0, compared(original, original).aggregate(), TOLERANCE);
    }

    @Test
    void frequencyShiftedControlSharesNoBand() {
        SpectralComparison comparison = compared(
                sparse(new int[] {1, 2}, new double[] {1.0, 0.5}, new double[] {0.0, 0.3}),
                sparse(new int[] {3, 4}, new double[] {1.0, 0.0}, new double[] {0.0, 0.0}));

        assertEquals(List.of(), comparison.bands());
        assertEquals(0.5, comparison.aggregate());
    }

    @Test
    void rejectsNullPatterns() {
        assertThrows(NullPointerException.class, () -> dynamics.compare(null, p4()));
        assertThrows(NullPointerException.class, () -> dynamics.compare(p4(), null));
    }

    @Test
    void resultValuesValidateTheirRanges() {
        assertThrows(IllegalArgumentException.class, () -> new SpectralComparison(List.of(), 1.0, 1.0, 0, 0,
                1.5, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new SpectralComparison(List.of(
                new BandContribution(0, 2, 1.0, 1.0, 0.0, 1.0, 1.0, true),
                new BandContribution(0, 1, 1.0, 1.0, 0.0, 1.0, 1.0, true)), 1.0, 1.0, 0, 0, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new BandContribution(0, 1, 1.0, 1.0, -PI, 1.0, 1.0,
                true));
        assertThrows(IllegalArgumentException.class, () -> new Incompatible(IncompatibilityReason.UNIT, " "));
    }

    private SpectralComparison compared(SpectralPattern first, SpectralPattern second) {
        return assertInstanceOf(SpectralComparison.class, dynamics.compare(first, second));
    }

    private void assertReason(IncompatibilityReason reason, SpectralPattern first, SpectralPattern second) {
        Incompatible incompatible = assertInstanceOf(Incompatible.class, dynamics.compare(first, second));
        assertEquals(reason, incompatible.reason());
        assertEquals(reason, assertInstanceOf(Incompatible.class, dynamics.compare(second, first)).reason());
    }

    private static SpectralPattern decompose(int n, long seed) {
        var random = new SplittableRandom(seed);
        var samples = new double[n];
        for (int i = 0; i < n; i++) {
            samples[i] = random.nextDouble(-10.0, 10.0);
        }
        var wave = new SampledWave(samples, n, SampleUnit.HERTZ, PROVENANCE);
        return assertInstanceOf(Decomposed.class, new DftWaveDecomposer().decompose(wave)).pattern();
    }

    /** Replaces the phase of every complex bin; the DC and Nyquist bins must keep phase 0 or π. */
    private static SpectralPattern withRandomPhases(SpectralPattern pattern, SplittableRandom random) {
        double[] phases = pattern.phases();
        int last = pattern.componentCount() - 1;
        for (int i = 1; i < last; i++) {
            phases[i] = SpectralPhase.wrap(random.nextDouble(0.0, 2.0 * PI));
        }
        return SpectralPattern.of(pattern.frame(), pattern.sourceEnergy(), pattern.provenance(), pattern.levels(),
                pattern.indices(), pattern.magnitudes(), phases);
    }
}
